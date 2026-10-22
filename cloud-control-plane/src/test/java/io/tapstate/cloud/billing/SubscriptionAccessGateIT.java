package io.tapstate.cloud.billing;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.payment.stripe.StripeProductService;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.SpecConfig;
import io.tapstate.cloud.subscription.SpecConfigService;
import io.tapstate.cloud.subscription.Subscription;
import io.tapstate.cloud.subscription.SubscriptionItem;
import io.tapstate.cloud.subscription.SubscriptionItemRepository;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionService;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import io.tapstate.cloud.version.ClusterVersionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who is allowed to have infrastructure.
 * <p>
 * Four states stand on their own: no subscription (a Checkout shell), on trial,
 * paid and active, and expired. Only the two live states may provision — a shell
 * has not paid for anything, and a cancelled subscription has nothing left to
 * allocate. The status predicate and the provisioning gate must agree; asserting
 * only one of them would let the two drift apart.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionAccessGateIT {

    private static final String ORG_ID = "6ad700000000000000000001";

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private SubscriptionItemRepository itemRepository;
    @Mock
    private SubscriptionStateMachineService subscriptionStateMachineService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ClusterService clusterService;
    @Mock
    private MdbAllocationService mdbAllocationService;
    @Mock
    private DeploymentStepRecorder deploymentStepRecorder;
    @Mock
    private ClusterVersionService clusterVersionService;
    @Mock
    private SpecConfigService specConfigService;
    @Mock
    private StripeProductService stripeProductService;
    @Mock
    private PaymentGateway paymentGateway;

    private SubscriptionService subscriptionService;
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService(
                subscriptionRepository, itemRepository, subscriptionStateMachineService,
                eventPublisher, clusterService, mdbAllocationService, deploymentStepRecorder,
                clusterVersionService, specConfigService, stripeProductService, paymentGateway);

        subscription = Subscription.builder()
                .id(new ObjectId())
                .orgId(new ObjectId(ORG_ID))
                .status(SubscriptionStatus.PENDING_PAYMENT)
                .specConfig(SpecConfig.builder().key("2c8g").trialDays(7).enabled(true).build())
                .currentPeriodStart(Instant.now())
                .currentPeriodEnd(Instant.now().plusSeconds(7 * 24 * 3600))
                .build();
        lenient().when(subscriptionRepository.findById(any())).thenReturn(Optional.of(subscription));
        lenient().when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("gate: trial and active allow provisioning; shell, past due, pending cancel and cancelled do not")
    void onlyLiveStatesAllowProvisioning() {
        assertThat(SubscriptionStatus.PENDING_PAYMENT.allowsProvisioning()).isFalse();
        assertThat(SubscriptionStatus.TRIAL.allowsProvisioning()).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.allowsProvisioning()).isTrue();
        assertThat(SubscriptionStatus.PAST_DUE.allowsProvisioning()).isFalse();
        assertThat(SubscriptionStatus.PENDING_CANCEL.allowsProvisioning()).isFalse();
        assertThat(SubscriptionStatus.CANCELLED.allowsProvisioning()).isFalse();
    }

    @Test
    @DisplayName("no subscription: an unpaid Checkout shell cannot be provisioned")
    void shellCannotProvision() {
        subscription.setStatus(SubscriptionStatus.PENDING_PAYMENT);

        BusinessException refused = assertThrows(BusinessException.class,
                () -> subscriptionService.finalizeProvisioning(
                        subscription.getId().toHexString(), "ap-southeast-1", "gate-shell", "buyer@example.com"));

        assertThat(refused.getErrorCode()).isEqualTo(AppErrorCode.SUBSCRIPTION_PENDING_PAYMENT);
        verify(clusterService, never()).createCluster(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("expired: a cancelled subscription cannot be provisioned")
    void cancelledCannotProvision() {
        subscription.setStatus(SubscriptionStatus.CANCELLED);

        BusinessException refused = assertThrows(BusinessException.class,
                () -> subscriptionService.finalizeProvisioning(
                        subscription.getId().toHexString(), "ap-southeast-1", "gate-cancelled", "buyer@example.com"));

        assertThat(refused.getErrorCode()).isEqualTo(AppErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        verify(clusterService, never()).createCluster(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("trial: a trial subscription provisions its instance")
    void trialCanProvision() {
        subscription.setStatus(SubscriptionStatus.TRIAL);
        stubInfrastructure();

        Subscription provisioned = subscriptionService.finalizeProvisioning(
                subscription.getId().toHexString(), "ap-southeast-1", "gate-trial", "buyer@example.com");

        assertThat(provisioned.getResourceState()).isNotNull();
        verify(clusterService).createCluster(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("active: a paid subscription provisions its instance")
    void activeCanProvision() {
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        stubInfrastructure();

        Subscription provisioned = subscriptionService.finalizeProvisioning(
                subscription.getId().toHexString(), "ap-southeast-1", "gate-active", "buyer@example.com");

        assertThat(provisioned.getResourceState()).isNotNull();
        verify(clusterService).createCluster(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    private void stubInfrastructure() {
        var clusterVersion = mock(io.tapstate.cloud.version.ClusterVersion.class);
        when(clusterVersion.getVersion()).thenReturn("1.0.0");
        when(clusterVersionService.findClusterLastVersion()).thenReturn(clusterVersion);

        Cluster cluster = mock(Cluster.class);
        when(cluster.getId()).thenReturn(new ObjectId());
        when(clusterService.createCluster(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(cluster);

        MdbAllocation allocation = mock(MdbAllocation.class);
        when(allocation.getId()).thenReturn(new ObjectId());
        when(mdbAllocationService.allocateMdb(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(allocation);

        SubscriptionItem item = mock(SubscriptionItem.class);
        lenient().when(itemRepository.findBySubscriptionIdAndItemType(any(), any()))
                .thenReturn(Optional.of(item));
    }
}
