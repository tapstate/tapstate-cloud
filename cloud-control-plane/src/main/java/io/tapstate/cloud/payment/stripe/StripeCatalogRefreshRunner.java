package io.tapstate.cloud.payment.stripe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs {@link StripeProductService#refreshAll()} right after
 * {@link SpecConfigBootstrap}. Order 2 ensures the spec catalog exists
 * before Stripe products are loaded (the refresh validates specKey foreign
 * keys against {@code spec_config}).
 */
@Slf4j
@Component
@Order(2)
public class StripeCatalogRefreshRunner implements ApplicationRunner {

    private final StripeProductService service;

    public StripeCatalogRefreshRunner(StripeProductService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            int count = service.refreshAll();
            log.info("StripeCatalogRefreshRunner: boot refresh synced {} products", count);
        } catch (IntegrityException e) {
            // Integrity violation on boot is NOT fatal — the operator must fix
            // Stripe-side metadata. The service is functional; payment creation
            // will surface missing pricing later.
            log.error("StripeCatalogRefreshRunner: integrity check failed — check Stripe metadata: {}",
                    e.getMessage());
        } catch (Exception e) {
            log.error("StripeCatalogRefreshRunner: boot refresh failed — continuing startup", e);
        }
    }
}
