package io.tapstate.cloud.subscription;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.tapstate.cloud.common.api.ApiResponse;
import io.tapstate.cloud.common.api.BaseController;
import io.tapstate.cloud.payment.PaymentMethod;
import io.tapstate.cloud.payment.stripe.StripePriceEntry;
import io.tapstate.cloud.payment.stripe.StripeProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Public spec catalog endpoint — JOINs platform {@code spec_config} with
 * channel pricing ({@code stripe_products}) so the Console shows each spec
 * card alongside its monthly price, trial info and currency.
 * <p>
 * Only specs that are <b>enabled both on the platform AND on the Stripe
 * catalog</b> are returned. A spec missing pricing in the Stripe catalog is
 * excluded — creating a subscription for it would fail anyway.
 * <p>
 * {@code GET /v1/api/specs} is unauthenticated — Console needs to show
 * pricing on the landing page before the user signs in.
 */
@RestController
@RequestMapping("/v1/api")
@RequiredArgsConstructor
@Tag(name = "Spec Catalog", description = "Platform subscription specs with channel pricing")
public class SpecController extends BaseController {

    private final SpecConfigService specConfigService;
    private final StripeProductService stripeProductService;

    @GetMapping("/specs")
    @Operation(summary = "List enabled subscription specs with their channel pricing",
            description = "JOINs platform spec_config (enabled=true) with Stripe product catalog pricing " +
                    "(enabled=true on both Product and Price). Only specs with configured pricing are returned.")
    public ResponseEntity<ApiResponse<List<SpecWithPricingResponse>>> listSpecs() {
        List<SpecConfig> enabledSpecs = specConfigService.findAllEnabled();
        List<StripePriceEntry> enabledPrices = stripeProductService.findAllEnabledPriceEntries();

        // Build lookup: specKey → PriceEntry (one enabled price per specKey is enforced by refreshAll)
        Map<String, StripePriceEntry> priceBySpecKey = new java.util.HashMap<>();
        for (StripePriceEntry p : enabledPrices) {
            priceBySpecKey.putIfAbsent(p.specKey(), p);
        }

        List<SpecWithPricingResponse> result = new ArrayList<>();
        for (SpecConfig spec : enabledSpecs) {
            StripePriceEntry price = priceBySpecKey.get(spec.getKey());
            if (price == null) {
                continue; // No pricing configured — skip
            }
            result.add(new SpecWithPricingResponse(
                    spec.getKey(),
                    spec.getDisplayName(),
                    spec.getCpuCores(),
                    spec.getMemoryGb(),
                    spec.getTrialDays(),
                    spec.getMdbSizeGb(),
                    price.currency(),
                    price.unitPriceCents(),
                    price.priceId(),
                    PaymentMethod.STRIPE.name()
            ));
        }

        return ok(result);
    }

    /** One spec card — platform spec + channel pricing. */
    public record SpecWithPricingResponse(
            String key,
            String displayName,
            int cpuCores,
            int memoryGb,
            int trialDays,
            int mdbSizeGb,
            String currency,
            long unitPriceCents,
            String stripePriceId,
            String paymentMethod
    ) {}
}
