package io.tapstate.cloud.iac;

/**
 * Supported infrastructure cloud providers.
 * <p>
 * The {@code id} is the value used in configuration properties; the enum is the
 * type-safe identity exchanged between the generic IaC layer and implementations.
 */
public enum CloudProvider {

    /** Amazon Web Services. */
    AWS("aws"),

    /** Alibaba Cloud (future). */
    ALICLOUD("alicloud"),

    /** Google Cloud Platform (future). */
    GCP("gcp");

    private final String id;

    CloudProvider(String id) {
        this.id = id;
    }

    /** Configuration value of this provider. */
    public String id() {
        return id;
    }

    /**
     * Resolve a provider from its configuration id.
     *
     * @throws IllegalArgumentException when the id does not name a supported provider
     */
    public static CloudProvider fromId(String id) {
        for (CloudProvider provider : values()) {
            if (provider.id.equalsIgnoreCase(id)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unsupported cloud provider: " + id);
    }
}
