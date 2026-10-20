package io.tapstate.cloud.common.api;

/**
 * Typed business error codes — implements {@link ErrorCode} so
 * {@link MessageUtils#getMessage(Locale, ErrorCode, Object...)} resolves
 * locale-aware messages automatically.
 * <p>
 * Keep this enum as the single source of truth. The companion
 * {@link ErrorCodes} constants class wraps each enum value's code string
 * for backward-compatible callers.
 */
public enum AppErrorCode implements ErrorCode {

    // ---------- Auth ----------
    EMAIL_ALREADY_REGISTERED("auth.email-already-registered", "Email {0} is already registered"),
    INVALID_CREDENTIALS("auth.invalid-credentials", "Invalid email or password"),
    SESSION_NOT_FOUND("auth.session-not-found", "Session not found or expired"),
    UNAUTHORIZED("auth.unauthorized", "Authentication required to access this resource"),
    USER_NOT_FOUND("auth.user-not-found", "User {0} not found"),
    NONCE_REPLAY("auth.nonce-replay", "Duplicate C2 nonce — request rejected"),

    // ---------- Org ----------
    ORG_NOT_FOUND("org.not-found", "Organization {0} not found"),
    ORG_NOT_MEMBER("org.not-member", "User is not a member of organization {0}"),
    ORG_ALREADY_EXISTS("org.already-exists", "Organization with slug {0} already exists"),
    ORG_LIMIT_REACHED("org.limit-reached", "This account already belongs to an organization"),

    // ---------- Cluster ----------
    CLUSTER_NOT_FOUND("cluster.not-found", "Cluster {0} not found"),
    CLUSTER_ALREADY_EXISTS("cluster.already-exists", "Cluster '{0}' already exists in org {1}, region {2}"),
    CLUSTER_NAME_DNS_INVALID("cluster.name-dns-invalid", "Cluster name '{0}' yields invalid DNS label '{1}': use letters or digits (at most {2} characters after normalization)"),
    CLUSTER_DNS_LABEL_CONFLICT("cluster.dns-label-conflict", "DNS name '{0}' derived from the cluster name is already used by another active cluster"),
    CLUSTER_INVALID_STATE("cluster.invalid-state", "Operation not allowed in cluster state {0}"),
    CLUSTER_ORG_MISMATCH("cluster.org-mismatch", "Cluster {0} does not belong to organization {1}"),
    CLUSTER_CANCEL_NOT_ALLOWED("cluster.cancel-not-allowed", "Cluster cannot be cancelled while in state {0}"),
    CLUSTER_PROVISION_FAILED("cluster.provision-failed", "Cluster provisioning {0}"),

    // ---------- Cluster versions ----------
    CLUSTER_VERSION_UNAVAILABLE("cluster.version-unavailable", "No active Cluster version is available"),
    CLUSTER_VERSION_NOT_FOUND("cluster.version-not-found", "Cluster version {0} not found"),
    CLUSTER_VERSION_NOT_ACTIVE("cluster.version-not-active", "Cluster version {0} is not active"),
    CLUSTER_VERSION_DUPLICATE("cluster.version-duplicate", "Cluster version {0} already exists"),
    CLUSTER_VERSION_RELEASE_TOKEN_MISSING("cluster.release-token-missing", "Cluster release static token is required (Bearer header)"),
    CLUSTER_VERSION_RELEASE_TOKEN_INVALID("cluster.release-token-invalid", "Cluster release static token is invalid"),
    CLUSTER_VERSION_RELEASE_DISABLED("cluster.release-disabled", "Cluster version release endpoint is not configured (release token unset)"),

    // ---------- Infrastructure as Code ----------
    IAC_COMPUTE_PROVISION_FAILED("iac.compute-provision-failed", "Compute provisioning failed: {0}"),
    IAC_COMPUTE_TERMINATE_FAILED("iac.compute-terminate-failed", "Compute termination failed: {0}"),
    IAC_DNS_PROVIDER_FAILED("iac.dns-provider-failed", "DNS operation failed: {0}"),
    IAC_DNS_PROVIDER_UNAUTHORIZED("iac.dns-provider-unauthorized", "DNS provider credentials missing or rejected: {0}"),
    IAC_SSH_COMMAND_FAILED("iac.ssh-command-failed", "SSH operation failed: {0}"),
    IAC_HEALTH_CHECK_FAILED("iac.health-check-failed", "Cluster endpoint health check failed: {0}"),
    IAC_CRYPTO_KEY_MISSING("iac.crypto-key-missing", "SSH private-key master key is missing or invalid: {0}"),
    IAC_MDB_NOT_READY("iac.mdb-not-ready", "Allocated MongoDB database is not ready: {0}"),
    IAC_TLS_CERTIFICATE_UNAVAILABLE("iac.tls-certificate-unavailable", "No usable TLS certificate covering the Cluster domain: {0}"),
    IAC_TLS_CERTIFICATE_INSTALL_FAILED("iac.tls-certificate-install-failed", "TLS certificate installation on the Cluster node failed: {0}"),
    IAC_CLUSTER_VERSION_UNAVAILABLE("iac.cluster-version-unavailable", "No deployable Cluster version is available: {0}"),

    // ---------- Region ----------
    REGION_NOT_FOUND("region.not-found", "Region {0} is not available"),

    // ---------- Exchange (ADR-0125) ----------
    EXCHANGE_CODE_CONSUMED("exchange.code-consumed", "Exchange code already consumed"),
    EXCHANGE_CODE_EXPIRED("exchange.code-expired", "Exchange code has expired"),
    EXCHANGE_CODE_CLUSTER_MISMATCH("exchange.code-cluster-mismatch", "Exchange code does not belong to this cluster"),
    EXCHANGE_CLUSTER_IDENTITY_MISMATCH("exchange.cluster-identity-mismatch", "Cluster service identity secret is invalid"),

    // ---------- Subscription ----------
    SUBSCRIPTION_NOT_FOUND("subscription.not-found", "Subscription {0} not found"),
    SUBSCRIPTION_CANCELLED("subscription.cancelled", "Subscription is cancelled — provisioning is blocked"),
    SUBSCRIPTION_PAST_DUE("subscription.past-due", "Subscription is past due — provisioning is blocked until payment is made"),
    SUBSCRIPTION_SPEC_NOT_FOUND("subscription.spec-not-found", "Platform spec key {0} not found or deprecated"),
    SUBSCRIPTION_PRICING_NOT_CONFIGURED("subscription.pricing-not-configured", "No active Stripe pricing configured for spec {0} — admin must sync catalog"),
    SUBSCRIPTION_ALREADY_ACTIVE("subscription.already-active", "Subscription {0} is already active or pending payment — cannot create duplicate"),
    SUBSCRIPTION_CANNOT_CANCEL("subscription.cannot-cancel", "Subscription cannot be cancelled from current state"),
    SUBSCRIPTION_PENDING_PAYMENT("subscription.pending-payment", "Subscription is still pending payment — waiting for Stripe checkout"),

    // ---------- Stripe integration ----------
    STRIPE_WEBHOOK_SIGNATURE_INVALID("stripe.webhook-signature-invalid", "Stripe webhook signature verification failed"),
    STRIPE_CHECKOUT_FAILED("stripe.checkout-failed", "Failed to create Stripe Checkout session: {0}"),
    STRIPE_INTEGRATION_ERROR("stripe.integration-error", "Stripe API call failed: {0}"),

    // ---------- SDK ----------
    SDK_TOKEN_MISSING("sdk.token-missing", "SDK static token is required (Bearer header)"),
    SDK_TOKEN_INVALID("sdk.token-invalid", "SDK static token is invalid or revoked"),
    SAMPLE_SOURCE_CREDENTIALS_NOT_CONFIGURED(
        "sample-source.credentials-not-configured", "Sample source credentials are not configured"),

    // ---------- Deployment progress ----------
    DEPLOYMENT_OP_TYPE_INVALID("deployment.op-type-invalid", "Invalid deployment opType {0}"),

    // ---------- Generic ----------
    BAD_REQUEST("bad_request", "Invalid request: {0}"),
    SYSTEM_ERROR("system_error", "Internal server error");

    private final String code;
    private final String description;

    AppErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "[" + code + "] " + description;
    }
}
