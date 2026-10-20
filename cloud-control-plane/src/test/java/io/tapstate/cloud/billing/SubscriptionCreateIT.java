package io.tapstate.cloud.billing;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.stripe.Stripe;
import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.payment.stripe.StripeCustomerService;
import io.tapstate.cloud.payment.stripe.StripeEventRepository;
import io.tapstate.cloud.payment.stripe.StripePaymentService;
import io.tapstate.cloud.payment.stripe.StripePriceEntry;
import io.tapstate.cloud.payment.stripe.StripeProductService;
import io.tapstate.cloud.payment.stripe.StripeWebhookConfig;
import io.tapstate.cloud.payment.stripe.StripeWebhookEventService;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.SpecConfig;
import io.tapstate.cloud.subscription.SpecConfigService;
import io.tapstate.cloud.subscription.Subscription;
import io.tapstate.cloud.subscription.SubscriptionItem;
import io.tapstate.cloud.subscription.SubscriptionItemRepository;
import io.tapstate.cloud.subscription.SubscriptionItemType;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionService;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import io.tapstate.cloud.subscription.fsm.SubscriptionEventType;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import io.tapstate.cloud.version.ClusterVersionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Create-side subscription flow against a stubbed Stripe endpoint.
 * <p>
 * Drives the real {@link StripePaymentService} (so the SDK really encodes a
 * Checkout Session request and really parses the response) and the real webhook
 * dispatcher, with the control-plane store and the infrastructure services
 * mocked. What the scenario is about is duplication: one checkout attempt and
 * one webhook delivery may produce exactly one subscription, one activation and
 * one provisioning request — a retry or a replay must not produce a second one.
 * <p>
 * The state machine is a stub here; the real transitions are covered end to end
 * against a live control plane.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionCreateIT {

    @RegisterExtension
    static WireMockExtension stripe = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private static final String ORG_ID = "6ad700000000000000000001";
    private static final String EMAIL = "buyer@example.com";

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
    private StripeEventRepository stripeEventRepository;

    private SubscriptionService subscriptionService;
    private StripeWebhookEventService webhookEventService;

    /** The shell under test — the repository mocks hand out this one instance. */
    private Subscription shell;

    /** Audit rows by Stripe event id, so a replay sees the first delivery. */
    private final Map<String, io.tapstate.cloud.payment.stripe.StripeEvent> auditRows = new HashMap<>();

    @BeforeAll
    static void pointTheSdkAtTheStub() {
        Stripe.overrideApiBase(stripe.baseUrl());
    }

    @AfterAll
    static void restoreTheDefaultApiBase() {
        Stripe.overrideApiBase("https://api.stripe.com");
    }

    @BeforeEach
    void setUp() {
        StripeWebhookConfig config = new StripeWebhookConfig("sk_test_it", StripeStub.WEBHOOK_SECRET, stripe.baseUrl());
        StripePaymentService stripePaymentService = new StripePaymentService(
                config, new StripeCustomerService(config), null);

        subscriptionService = new SubscriptionService(
                subscriptionRepository, itemRepository, subscriptionStateMachineService,
                eventPublisher, clusterService, mdbAllocationService, deploymentStepRecorder,
                clusterVersionService, specConfigService, stripeProductService, stripePaymentService);
        webhookEventService = new StripeWebhookEventService(
                stripeEventRepository, subscriptionRepository, subscriptionService,
                subscriptionStateMachineService, config);

        StripeStub.checkout(stripe);

        lenient().when(subscriptionRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(shell));
        lenient().when(subscriptionRepository.save(any())).thenAnswer(invocation -> {
            Subscription saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(new ObjectId());
            }
            shell = saved;
            return saved;
        });
        lenient().when(subscriptionRepository.findByStripeCheckoutSessionId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(shell));
        lenient().when(stripeEventRepository.findByStripeEventId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(auditRows.get(invocation.getArgument(0))));
        lenient().when(stripeEventRepository.save(any())).thenAnswer(invocation -> {
            io.tapstate.cloud.payment.stripe.StripeEvent row = invocation.getArgument(0);
            auditRows.put(row.getStripeEventId(), row);
            return row;
        });

        // The real FSM persists the target status; this stub does the same for the
        // transitions this scenario drives.
        lenient().when(subscriptionStateMachineService.transition(any(), any(), anyString()))
                .thenAnswer(invocation -> {
                    SubscriptionEventType event = invocation.getArgument(1);
                    SubscriptionStatus next = switch (event) {
                        case CHECKOUT_COMPLETED -> SubscriptionStatus.ACTIVE;
                        case CHECKOUT_COMPLETED_TRIAL -> SubscriptionStatus.TRIAL;
                        case CANCEL_AT_PERIOD_END_REQUESTED -> SubscriptionStatus.PENDING_CANCEL;
                        case IMMEDIATE_CANCEL, PERIOD_END_REAPED -> SubscriptionStatus.CANCELLED;
                        case PAYMENT_CONFIRMED, PAYMENT_RECEIVED -> SubscriptionStatus.ACTIVE;
                        default -> shell.getStatus();
                    };
                    shell.setStatus(next);
                    subscriptionRepository.save(shell);
                    return SubscriptionStateMachineService.TransitionResult.accepted(next);
                });
    }

    @Test
    @DisplayName("checkout + webhook: one subscription, activated once, provisioned once")
    void createThenActivateProvisionsOnce() {
        stubCatalogue("2c8g");
        SubscriptionService.CheckoutResult checkout = subscriptionService
                .createCheckoutSession(ORG_ID, "2c8g", EMAIL, "req-1");

        assertThat(checkout.sessionUrl()).isEqualTo(StripeStub.CHECKOUT_URL);
        assertThat(checkout.subscription().getStatus()).isEqualTo(SubscriptionStatus.PENDING_PAYMENT);

        // The trial spec starts in TRIAL: the card is bound, nothing is charged yet.
        PaymentGateway.WebhookResult first = deliverCheckoutCompleted();
        assertThat(first.processed()).isTrue();
        assertThat(shell.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(shell.isProvisionInitiated()).isTrue();
        assertThat(shell.getStripe().getSubscriptionId()).isEqualTo(StripeStub.SUBSCRIPTION_ID);
        assertThat(shell.getStripe().isAutoRenewAuthorized()).isTrue();

        // Exactly one Checkout Session was created — a replay is not a second charge.
        stripe.verify(1, postRequestedFor(urlPathEqualTo("/v1/checkout/sessions")));
    }

    @Test
    @DisplayName("webhook replay: a second delivery does not re-activate or re-provision")
    void webhookReplayIsIdempotent() {
        stubCatalogue("4c16g");
        subscriptionService.createCheckoutSession(ORG_ID, "4c16g", EMAIL, "req-1");
        deliverCheckoutCompleted();

        assertThat(shell.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        SubscriptionStatus afterFirstDelivery = shell.getStatus();
        Instant updatedAtAfterFirstDelivery = shell.getUpdatedAt();

        PaymentGateway.WebhookResult replay = deliverCheckoutCompleted();

        assertThat(replay.processed()).isFalse();
        assertThat(replay.message()).contains("already processed");
        assertThat(shell.getStatus()).isEqualTo(afterFirstDelivery);
        assertThat(shell.getUpdatedAt()).isEqualTo(updatedAtAfterFirstDelivery);
        assertThat(auditRows).hasSize(1);
    }

    @Test
    @DisplayName("duplicate checkout submit: the same clientRequestId does not open a second session")
    void duplicateCreateIsRejectedWithoutASecondSession() {
        stubCatalogue("4c16g");
        subscriptionService.createCheckoutSession(ORG_ID, "4c16g", EMAIL, "req-same");
        when(subscriptionRepository.findByClientRequestIdAndOrgId(eq("req-same"), any()))
                .thenReturn(Optional.of(shell));

        BusinessException duplicate = assertThrows(BusinessException.class, () ->
                subscriptionService.createCheckoutSession(ORG_ID, "4c16g", EMAIL, "req-same"));

        assertThat(duplicate.getErrorCode()).isEqualTo(AppErrorCode.SUBSCRIPTION_ALREADY_ACTIVE);
        assertThat(duplicate.getHttpStatus()).isEqualTo(409);
        stripe.verify(1, postRequestedFor(urlPathEqualTo("/v1/checkout/sessions")));
    }

    @Test
    @DisplayName("finalize: provisioning is requested once even when the client retries")
    void finalizeIsRequestedOnce() {
        stubCatalogue("4c16g");
        subscriptionService.createCheckoutSession(ORG_ID, "4c16g", EMAIL, "req-1");
        deliverCheckoutCompleted();

        Cluster cluster = mock(Cluster.class);
        when(cluster.getId()).thenReturn(new ObjectId());
        MdbAllocation allocation = mock(MdbAllocation.class);
        when(allocation.getId()).thenReturn(new ObjectId());
        var clusterVersion = mock(io.tapstate.cloud.version.ClusterVersion.class);
        when(clusterVersion.getVersion()).thenReturn("1.0.0");
        when(clusterVersionService.findClusterLastVersion()).thenReturn(clusterVersion);
        when(clusterService.createCluster(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(cluster);
        when(mdbAllocationService.allocateMdb(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(allocation);
        SubscriptionItem item = mock(SubscriptionItem.class);
        lenient().when(itemRepository.findBySubscriptionIdAndItemType(any(), any()))
                .thenReturn(Optional.of(item));

        subscriptionService.finalizeProvisioning(shell.getId().toHexString(), "ap-southeast-1",
                "it-cluster", EMAIL);
        subscriptionService.finalizeProvisioning(shell.getId().toHexString(), "ap-southeast-1",
                "it-cluster", EMAIL);

        verify(clusterService, times(1)).createCluster(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(mdbAllocationService, times(1))
                .allocateMdb(anyString(), anyString(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- helpers ---------------------------------------------------------

    private PaymentGateway.WebhookResult deliverCheckoutCompleted() {
        String payload = StripeStub.checkoutCompleted(StripeStub.CHECKOUT_SESSION_ID);
        return webhookEventService.dispatch(new PaymentGateway.RawWebhookEvent(
                payload.getBytes(StandardCharsets.UTF_8),
                StripeStub.stripeSignatureHeader(payload),
                Map.of()));
    }

    private void stubCatalogue(String specKey) {
        when(specConfigService.findEnabledByKey(specKey)).thenReturn(Optional.of(spec(specKey)));
        when(stripeProductService.findEnabledBySpecKey(specKey)).thenReturn(Optional.of(
                new StripePriceEntry("price_" + specKey, 19_900L, "usd", specKey, true, "month", Instant.now())));
        var activeVersion = mock(io.tapstate.cloud.version.ClusterVersion.class);
        lenient().when(activeVersion.getVersion()).thenReturn("1.0.0");
        lenient().when(clusterVersionService.findClusterLastVersion()).thenReturn(activeVersion);
    }

    /** The three shipped specs, as the catalogue would hand them to checkout. */
    private static SpecConfig spec(String key) {
        int trialDays = switch (key) {
            case "2c8g" -> 7;
            case "4c16g" -> 0;
            case "8c32g" -> 0;
            default -> throw new IllegalArgumentException("unknown spec " + key);
        };
        return SpecConfig.builder()
                .key(key)
                .displayName(key)
                .trialDays(trialDays)
                .enabled(true)
                .build();
    }
}
