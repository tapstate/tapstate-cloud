package io.tapstate.cloud.payment.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerSearchParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Looks up or creates a Stripe Customer for a Tapstate organization.
 * <p>
 * Customers are keyed by {@code orgId} (stored in the Customer's {@code metadata.org_id}
 * field). A lookup tries Stripe's search API first; if not found, a new Customer
 * is created with the orgId as metadata so subsequent lookups succeed.
 * <p>
 * This approach keeps the Tapstate-side {@code stripe_customers} collection simple
 * (we store the Stripe Customer ID once per org and reuse it) while still using
 * Stripe Customer records as the canonical payment-method and subscription owner.
 */
@Slf4j
@Service
public class StripeCustomerService {

    private final StripeWebhookConfig config;

    public StripeCustomerService(StripeWebhookConfig config) {
        this.config = config;
    }

    /**
     * Get or create a Stripe Customer for the given org.
     *
     * @param orgId  Tapstate organization ID (written into Stripe Customer metadata)
     * @param email  Primary contact email (used when creating a new Customer)
     * @return Stripe Customer ID
     */
    public String getOrCreate(String orgId, String email) {
        if (config.secretKey() == null || config.secretKey().isBlank()) {
            throw new IllegalStateException("Stripe secret key is not configured");
        }
        Stripe.apiKey = config.secretKey();

        Optional<String> existing = findByOrgId(orgId);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            Customer customer = Customer.create(CustomerCreateParams.builder()
                    .setEmail(email)
                    .putMetadata("org_id", orgId)
                    .putMetadata("tapstate_env", "prod")
                    .build());
            log.info("StripeCustomerService: created Customer {} for org {}", customer.getId(), orgId);
            return customer.getId();
        } catch (StripeException e) {
            throw new StripeIntegrationException("Failed to create Stripe Customer for org " + orgId, e);
        }
    }

    /** Look up an existing Stripe Customer by orgId metadata. */
    public Optional<String> findByOrgId(String orgId) {
        if (config.secretKey() == null || config.secretKey().isBlank()) {
            return Optional.empty();
        }
        Stripe.apiKey = config.secretKey();

        try {
            CustomerSearchParams params = CustomerSearchParams.builder()
                    .setQuery("metadata['org_id']:'" + orgId + "'")
                    .setLimit(1L)
                    .build();
            var results = Customer.search(params);
            if (results.getData().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(results.getData().get(0).getId());
        } catch (StripeException e) {
            log.warn("StripeCustomerService: search failed for org {} — will retry on create path", orgId, e);
            return Optional.empty();
        }
    }
}
