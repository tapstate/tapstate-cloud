package io.tapstate.cloud.billing;

import com.stripe.param.checkout.SessionCreateParams;
import io.tapstate.cloud.cluster.ClusterService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.mdb.MdbAllocationService;
import io.tapstate.cloud.payment.PaymentGateway;
import io.tapstate.cloud.payment.PaymentMethod;
import io.tapstate.cloud.payment.stripe.StripePaymentService;
import io.tapstate.cloud.payment.stripe.StripePriceEntry;
import io.tapstate.cloud.payment.stripe.StripeProductService;
import io.tapstate.cloud.payment.stripe.StripeWebhookConfig;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.SpecConfig;
import io.tapstate.cloud.subscription.SpecConfigService;
import io.tapstate.cloud.subscription.SubscriptionItemRepository;
import io.tapstate.cloud.subscription.SubscriptionRepository;
import io.tapstate.cloud.subscription.SubscriptionService;
import io.tapstate.cloud.subscription.SubscriptionStatus;
import io.tapstate.cloud.subscription.fsm.SubscriptionStateMachineService;
import io.tapstate.cloud.version.ClusterVersion;
import io.tapstate.cloud.version.ClusterVersionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trial policy of the shipped spec catalogue, and the price shape checkout will
 * accept.
 * <p>
 * The product promises a 7-day free trial on 2C8G and immediate billing on
 * 4C16G / 8C32G. That promise lives in two places at once: the catalogue
 * ({@code trialDays}) and the Checkout Session Stripe is asked to create
 * ({@code subscription_data.trial_period_days}). A catalogue entry that never
 * reaches the session is a trial the customer does not get, so both are asserted
 * here.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionTrialTest {

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

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService(
                subscriptionRepository, itemRepository, subscriptionStateMachineService,
                eventPublisher, clusterService, mdbAllocationService, deploymentStepRecorder,
                clusterVersionService, specConfigService, stripeProductService, paymentGateway);
        lenient().when(paymentGateway.getPaymentMethod()).thenReturn(PaymentMethod.STRIPE);
    }

    // ---- catalogue --------------------------------------------------------

    @Test
    @DisplayName("catalogue: 2C8G carries a 7-day trial; 4C16G and 8C32G carry none")
    void catalogueCarriesTrialOnlyFor2c8g() {
        assertThat(spec("2c8g").getTrialDays()).isEqualTo(7);
        assertThat(spec("2c8g").hasTrial()).isTrue();

        assertThat(spec("4c16g").getTrialDays()).isZero();
        assertThat(spec("4c16g").hasTrial()).isFalse();

        assertThat(spec("8c32g").getTrialDays()).isZero();
        assertThat(spec("8c32g").hasTrial()).isFalse();
    }

    // ---- checkout session ------------------------------------------------

    @Test
    @DisplayName("checkout: the trial reaches Stripe only for 2C8G — the other two bill on the first invoice")
    void checkoutAppliesTrialOnlyToTheTrialSpec() {
        assertThat(trialPeriodDays("2c8g")).isEqualTo(7L);
        assertThat(trialPeriodDays("4c16g")).isNull();
        assertThat(trialPeriodDays("8c32g")).isNull();
    }

    @Test
    @DisplayName("checkout: the session carries the resolved price and the spec key")
    void checkoutCarriesPriceAndSpecKey() {
        SessionCreateParams params = stripe()
                .buildSessionParams(request("2c8g", "price_2c8g"), "cus_test");

        assertThat(params.getLineItems()).hasSize(1);
        assertThat(params.getLineItems().get(0).getPrice()).isEqualTo("price_2c8g");
        assertThat(params.getMetadata()).containsEntry("spec_key", "2c8g");
        assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.SUBSCRIPTION);
    }

    // ---- pricing guard ---------------------------------------------------

    @Test
    @DisplayName("checkout: a non-USD price is refused instead of being charged")
    void checkoutRefusesNonUsdPrice() {
        when(specConfigService.findEnabledByKey("2c8g")).thenReturn(Optional.of(spec("2c8g")));
        when(stripeProductService.findEnabledBySpecKey("2c8g")).thenReturn(Optional.of(
                new StripePriceEntry("price_eur", 19_900L, "eur", "2c8g", true, "month", Instant.now())));

        BusinessException refused = assertThrows(BusinessException.class,
                () -> subscriptionService.createCheckoutSession(ORG_ID, "2c8g", "buyer@example.com", "req-1"));

        assertThat(refused.getErrorCode()).isEqualTo(AppErrorCode.SUBSCRIPTION_PRICING_NOT_CONFIGURED);
        verify(paymentGateway, never()).createSession(any());
    }

    @Test
    @DisplayName("checkout: an annual price is refused — only the monthly USD price is sold")
    void checkoutRefusesNonMonthlyPrice() {
        when(specConfigService.findEnabledByKey("2c8g")).thenReturn(Optional.of(spec("2c8g")));
        when(stripeProductService.findEnabledBySpecKey("2c8g")).thenReturn(Optional.of(
                new StripePriceEntry("price_year", 199_000L, "usd", "2c8g", true, "year", Instant.now())));

        BusinessException refused = assertThrows(BusinessException.class,
                () -> subscriptionService.createCheckoutSession(ORG_ID, "2c8g", "buyer@example.com", "req-1"));

        assertThat(refused.getErrorCode()).isEqualTo(AppErrorCode.SUBSCRIPTION_PRICING_NOT_CONFIGURED);
        verify(paymentGateway, never()).createSession(any());
    }

    @Test
    @DisplayName("checkout: a USD monthly trial price creates a PENDING_PAYMENT shell carrying the trial")
    void checkoutAcceptsUsdMonthlyTrialPrice() {
        when(specConfigService.findEnabledByKey("2c8g")).thenReturn(Optional.of(spec("2c8g")));
        when(stripeProductService.findEnabledBySpecKey("2c8g")).thenReturn(Optional.of(
                new StripePriceEntry("price_2c8g", 19_900L, "usd", "2c8g", true, "month", Instant.now())));
        ClusterVersion activeVersion = mock(ClusterVersion.class);
        when(clusterVersionService.findClusterLastVersion()).thenReturn(activeVersion);
        when(paymentGateway.createSession(any())).thenReturn(
                new PaymentGateway.PaymentSessionResult("https://checkout.stripe.test/cs_test_1", null));
        when(subscriptionRepository.save(any())).thenAnswer(invocation -> {
            io.tapstate.cloud.subscription.Subscription shell = invocation.getArgument(0);
            shell.setId(new ObjectId());
            return shell;
        });

        SubscriptionService.CheckoutResult result = subscriptionService.createCheckoutSession(
                ORG_ID, "2c8g", "buyer@example.com", "req-2");

        assertThat(result.subscription().getStatus()).isEqualTo(SubscriptionStatus.PENDING_PAYMENT);
        assertThat(result.subscription().getSpecConfig().getTrialDays()).isEqualTo(7);

        ArgumentCaptor<PaymentGateway.CreateSessionRequest> sent =
                ArgumentCaptor.forClass(PaymentGateway.CreateSessionRequest.class);
        verify(paymentGateway).createSession(sent.capture());
        assertThat(sent.getValue().specConfig().getTrialDays()).isEqualTo(7);
        assertThat(sent.getValue().stripePriceId()).isEqualTo("price_2c8g");
    }

    // ---- helpers ---------------------------------------------------------

    private static StripePaymentService stripe() {
        return new StripePaymentService(
                new StripeWebhookConfig("sk_test_unit", "whsec_unit", ""), null, null);
    }

    private static Long trialPeriodDays(String specKey) {
        SessionCreateParams params = stripe().buildSessionParams(
                request(specKey, "price_" + specKey), "cus_test");
        return params.getSubscriptionData() == null
                ? null
                : params.getSubscriptionData().getTrialPeriodDays();
    }

    private static PaymentGateway.CreateSessionRequest request(String specKey, String priceId) {
        return new PaymentGateway.CreateSessionRequest(
                ORG_ID, "buyer@example.com", spec(specKey), priceId);
    }

    /** Read one entry out of the shipped {@code spec-config.json} catalogue. */
    private static SpecConfig spec(String key) {
        List<Map<String, Object>> entries = catalogue();
        Map<String, Object> entry = entries.stream()
                .filter(e -> key.equals(e.get("key")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("spec-config.json has no entry for " + key));
        return SpecConfig.builder()
                .key(key)
                .displayName((String) entry.get("displayName"))
                .cpuCores(((Number) entry.get("cpuCores")).intValue())
                .memoryGb(((Number) entry.get("memoryGb")).intValue())
                .trialDays(((Number) entry.get("trialDays")).intValue())
                .mdbSizeGb(((Number) entry.get("mdbSizeGb")).intValue())
                .enabled(Boolean.TRUE.equals(entry.get("enabled")))
                .build();
    }

    private static List<Map<String, Object>> catalogue() {
        try (InputStream in = SubscriptionTrialTest.class.getResourceAsStream("/spec-config.json")) {
            assertThat(in).as("classpath:spec-config.json").isNotNull();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonMapper.builder().build()
                    .readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new AssertionError("cannot read spec-config.json", e);
        }
    }
}
