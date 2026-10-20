package io.tapstate.cloud.payment.stripe;

import com.stripe.Stripe;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.param.PriceListParams;
import com.stripe.param.ProductListParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.ReplaceRootOperation;
import org.springframework.data.mongodb.core.aggregation.UnwindOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service layer for {@code stripe_products} — syncs Stripe Product/Price data
 * into the local catalog and provides aggregation-backed lookups.
 * <p>
 * Sync runs on boot (post-SpecConfigBootstrap) and on a nightly schedule. It
 * can also be triggered manually via the admin endpoint
 * {@code POST /v1/api/internal/payment/stripe/products/refresh}.
 *
 * <h3>Cross-document uniqueness</h3>
 * MongoDB cannot enforce a unique index across array sub-fields, so the
 * invariant "at most one enabled Price per specKey across all Products" is
 * validated here in application code. If a sync discovers a duplicate specKey
 * it aborts with {@link IntegrityException} — the operator must fix the
 * duplicate on the Stripe side before re-running.
 */
@Slf4j
@Service
public class StripeProductService {

    private final StripeProductRepository repository;
    private final MongoTemplate mongoTemplate;
    private final StripeWebhookConfig webhookConfig;

    public StripeProductService(StripeProductRepository repository,
                                MongoTemplate mongoTemplate,
                                StripeWebhookConfig webhookConfig) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.webhookConfig = webhookConfig;
    }

    // ---- sync from Stripe ------------------------------------------------

    /**
     * Pull every Stripe Product marked {@code catalog=tapstate}, list all their
     * active recurring monthly USD Prices, and upsert into {@code stripe_products}.
     * <p>
     * Prices are validated against {@code spec_config} (must exist and be enabled).
     * Duplicate {@code specKey} across Products raises {@link IntegrityException}.
     * Each Product document's {@code prices[]} array is <b>wholesale replaced</b> —
     * Stripe itself doesn't retain old Price versions, so disabled historical rows
     * add noise. The integrity check at the beginning of this method ensures only
     * enabled Prices for valid specKeys end up in the DB.
     */
    public int refreshAll() {
        if (webhookConfig.secretKey() == null || webhookConfig.secretKey().isBlank()) {
            log.warn("Stripe secret key is not configured — skipping stripe_products sync");
            return 0;
        }
        Stripe.apiKey = webhookConfig.secretKey();

        // 1. List all Stripe Products tagged catalog=tapstate
        List<Product> tapstateProducts = new ArrayList<>();
        ProductListParams params = ProductListParams.builder()
                .putExpand("data.default_price")
                .putLimit(100L)
                .build();
        Product.Collection productColl = null;
        do {
            if (productColl == null) {
                productColl = Product.list(params);
            } else {
                productColl = productColl.list();
            }
            for (Product p : productColl.getData()) {
                if ("tapstate".equals(p.getMetadata().get("catalog"))) {
                    tapstateProducts.add(p);
                }
            }
        } while (productColl.getHasMore());

        // 2. List Prices per Product (recurring monthly USD, active only)
        int totalUpserted = 0;
        Map<String, String> seenSpecKeys = new HashMap<>(); // specKey → productId (cross-doc uniqueness)

        for (Product stripeProduct : tapstateProducts) {
            List<Price> stripePrices = new ArrayList<>();
            PriceListParams pParams = PriceListParams.builder()
                    .setProduct(stripeProduct.getId())
                    .setType("recurring")
                    .putLimit(100L)
                    .build();
            Price.Collection priceColl = null;
            do {
                if (priceColl == null) {
                    priceColl = Price.list(pParams);
                } else {
                    priceColl = priceColl.list();
                }
                stripePrices.addAll(priceColl.getData());
            } while (priceColl.getHasMore());

            // Filter: USD + monthly + active + valid spec_key
            List<StripePriceEntry> priceEntries = new ArrayList<>();
            for (Price price : stripePrices) {
                if (!"USD".equalsIgnoreCase(price.getCurrency())) continue;
                if (price.getRecurring() == null || !"month".equalsIgnoreCase(price.getRecurring().getInterval())) continue;
                if (Boolean.FALSE.equals(price.getActive())) continue;

                String specKey = price.getMetadata().get("spec_key");
                if (specKey == null || specKey.isBlank()) {
                    log.warn("Stripe Price {} missing metadata.spec_key — skipping", price.getId());
                    continue;
                }

                // Cross-document uniqueness check
                if (seenSpecKeys.containsKey(specKey)) {
                    throw new IntegrityException(
                            "Duplicate specKey '{}' across Stripe Products: {} and {}. " +
                                    "Fix the duplicate on Stripe and re-run refreshAll.",
                            specKey, seenSpecKeys.get(specKey), stripeProduct.getId());
                }
                seenSpecKeys.put(specKey, stripeProduct.getId());

                priceEntries.add(new StripePriceEntry(
                        price.getId(),
                        price.getUnitAmount() != null ? price.getUnitAmount() : 0L,
                        price.getCurrency(),
                        specKey,
                        true,
                        price.getRecurring().getInterval(),
                        Instant.now()));
            }

            // 3. Upsert Product document (full replacement of prices[])
            StripeProduct doc = StripeProduct.builder()
                    .productId(stripeProduct.getId())
                    .productName(stripeProduct.getName())
                    .enabled(stripeProduct.getActive() != null ? stripeProduct.getActive() : true)
                    .prices(priceEntries)
                    .updatedAt(Instant.now())
                    .build();

            Query query = Query.query(Criteria.where("productId").is(doc.getProductId()));
            mongoTemplate.upsert(query, doc, StripeProduct.class);
            totalUpserted++;
            log.info("StripeProductService: upserted product {} ({} prices, enabled={})",
                    doc.getProductId(), priceEntries.size(), doc.isEnabled());
        }

        // 4. Mark Products no longer in Stripe catalog as disabled (don't delete —
        //    active Subscriptions may still reference their Price IDs).
        List<String> productIdsInStripe = tapstateProducts.stream()
                .map(Product::getId).toList();
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("enabled").is(true)
                        .and("productId").nin(productIdsInStripe)),
                new Update().set("enabled", false).set("updatedAt", Instant.now()),
                StripeProduct.class);

        log.info("StripeProductService: sync complete — {} products upserted, {} specKeys",
                totalUpserted, seenSpecKeys.size());
        return totalUpserted;
    }

    // ---- aggregation-backed lookups -------------------------------------

    /**
     * Find the single enabled Price for a given specKey across all enabled Products.
     * <p>
     * Uses {@code $unwind} + {@code $replaceRoot} so the unwound sub-document maps
     * directly to {@link StripePriceEntry}. IntegrityException is already raised
     * during {@code refreshAll()} if a duplicate specKey would be produced, so we
     * only need a defensive size check here.
     */
    public Optional<StripePriceEntry> findEnabledBySpecKey(String specKey) {
        Aggregation agg = Aggregation.newAggregation(
                new MatchOperation(Criteria.where("enabled").is(true)),
                new UnwindOperation(Aggregation.fields().and("prices")),
                new MatchOperation(Criteria.where("prices.enabled").is(true)
                        .and("prices.specKey").is(specKey)),
                new ReplaceRootOperation(Aggregation.fields().and("prices"))
        );
        List<StripePriceEntry> results = mongoTemplate.aggregate(
                agg, StripeProduct.class, StripePriceEntry.class).getMappedResults();

        if (results.size() > 1) {
            throw new IntegrityException(
                    "Multiple enabled prices for specKey '{}' — integrity violated, refreshAll should have caught this",
                    specKey);
        }
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Return every enabled Price from every enabled Product — used by the
     * {@code /v1/api/specs} public endpoint to JOIN with spec_config.
     */
    public List<StripePriceEntry> findAllEnabledPriceEntries() {
        Aggregation agg = Aggregation.newAggregation(
                new MatchOperation(Criteria.where("enabled").is(true)),
                new UnwindOperation(Aggregation.fields().and("prices")),
                new MatchOperation(Criteria.where("prices.enabled").is(true)),
                new ReplaceRootOperation(Aggregation.fields().and("prices"))
        );
        return mongoTemplate.aggregate(
                agg, StripeProduct.class, StripePriceEntry.class).getMappedResults();
    }

    // ---- scheduled + admin triggers -------------------------------------

    /** Nightly catalog sync (03:00 UTC). Cheap enough — just a few Stripe API calls. */
    @Scheduled(cron = "0 0 3 * * ?", zone = "UTC")
    public void scheduledRefresh() {
        try {
            log.info("StripeProductService: nightly scheduled refresh starting");
            refreshAll();
        } catch (Exception e) {
            log.error("StripeProductService: nightly refresh failed", e);
        }
    }

    /** Admin endpoint — immediate one-off refresh. */
    public int refreshAllNow() {
        return refreshAll();
    }
}
