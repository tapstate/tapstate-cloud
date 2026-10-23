package io.tapstate.cloud.billing;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.stripe.Stripe;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.event.EventCenterService;
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
import io.tapstate.cloud.subscription.SubscriptionItemRepository;
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

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Trial end against a stubbed Stripe endpoint.
 * <p>
 * A card-less trial has no way to convert: Stripe's {@code trial_will_end} notice
 * is the last moment we can stop it turning into an unpaid invoice. Without a
 * payment method the subscription is scheduled to cancel at trial end, so the
 * follow-up {@code customer.subscription.deleted} releases the instance. With a
 * card on file nothing is touched — the first invoice converts the trial.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionTrialWillEndIT {

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
    private Subscription shell;
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
                subscriptionStateMachineService, stripePaymentService, config,
                mock(EventCenterService.class));

        StripeStub.checkout(stripe);

        lenient().when(subscriptionRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(shell));
        lenient().when(subscriptionRepository.findByIdAndOrgId(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(shell));
        lenient().when(subscriptionRepository.findByStripeSubscriptionId(anyString()))
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
    @DisplayName("trial ends without a card: cancellation is scheduled so Stripe ends it, releasing the instance")
    void trialWithoutCardIsScheduledForCancellation() {
        StripeStub.trialSubscription(stripe, null);
        startTrial();

        PaymentGateway.WebhookResult result = deliverTrialWillEnd("evt_trial_end_no_card");

        assertThat(result.processed()).isTrue();
        assertThat(result.message()).contains("PROCESSED");
        assertThat(shell.getStatus()).isEqualTo(SubscriptionStatus.PENDING_CANCEL);
        stripe.verify(1, postRequestedFor(urlPathEqualTo("/v1/subscriptions/" + StripeStub.SUBSCRIPTION_ID))
                .withRequestBody(containing("cancel_at_period_end=true")));
    }

    @Test
    @DisplayName("trial ends with a card on file: nothing is cancelled — the first invoice converts it")
    void trialWithCardIsLeftToConvert() {
        StripeStub.trialSubscription(stripe, "pm_it_stub");
        startTrial();

        PaymentGateway.WebhookResult result = deliverTrialWillEnd("evt_trial_end_with_card");

        assertThat(result.message()).contains("SKIPPED");
        assertThat(shell.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        stripe.verify(0, postRequestedFor(urlPathEqualTo("/v1/subscriptions/" + StripeStub.SUBSCRIPTION_ID)));
    }

    @Test
    @DisplayName("trial_will_end on a subscription that already left the trial is ignored")
    void trialWillEndOnNonTrialSubscriptionIsIgnored() {
        StripeStub.trialSubscription(stripe, null);
        activate("4c16g");
        assertThat(shell.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        PaymentGateway.WebhookResult result = deliverTrialWillEnd("evt_trial_end_not_trial");

        assertThat(result.message()).contains("not in trial");
        assertThat(shell.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        stripe.verify(0, postRequestedFor(urlPathEqualTo("/v1/subscriptions/" + StripeStub.SUBSCRIPTION_ID)));
    }

    // ---- helpers ---------------------------------------------------------

    /** Checkout + webhook for the trial spec, leaving the shell in TRIAL. */
    private void startTrial() {
        activate("2c8g");
        assertThat(shell.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    }

    private PaymentGateway.WebhookResult deliverTrialWillEnd(String eventId) {
        String payload = StripeStub.trialWillEnd(eventId);
        return webhookEventService.dispatch(new PaymentGateway.RawWebhookEvent(
                payload.getBytes(StandardCharsets.UTF_8),
                StripeStub.stripeSignatureHeader(payload),
                Map.of()));
    }

    /** Checkout + webhook, leaving an activated subscription in {@link #shell}. */
    private void activate(String specKey) {
        stubCatalogue(specKey);
        subscriptionService.createCheckoutSession(ORG_ID, specKey, EMAIL, "req-" + specKey);
        String payload = StripeStub.checkoutCompleted(StripeStub.CHECKOUT_SESSION_ID);
        webhookEventService.dispatch(new PaymentGateway.RawWebhookEvent(
                payload.getBytes(StandardCharsets.UTF_8),
                StripeStub.stripeSignatureHeader(payload),
                Map.of()));
        assertThat(shell.getStatus()).isIn(SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE);
        assertThat(shell.getStripe().getSubscriptionId()).isEqualTo(StripeStub.SUBSCRIPTION_ID);
    }

    private void stubCatalogue(String specKey) {
        when(specConfigService.findEnabledByKey(specKey)).thenReturn(Optional.of(spec(specKey)));
        when(stripeProductService.findEnabledBySpecKey(specKey)).thenReturn(Optional.of(
                new StripePriceEntry("price_" + specKey, 39_900L, "usd", specKey, true, "month", Instant.now())));
        var activeVersion = mock(io.tapstate.cloud.version.ClusterVersion.class);
        lenient().when(clusterVersionService.findClusterLastVersion()).thenReturn(activeVersion);
    }

    private static SpecConfig spec(String key) {
        return SpecConfig.builder()
                .key(key)
                .displayName(key)
                .trialDays("2c8g".equals(key) ? 7 : 0)
                .enabled(true)
                .build();
    }
}
