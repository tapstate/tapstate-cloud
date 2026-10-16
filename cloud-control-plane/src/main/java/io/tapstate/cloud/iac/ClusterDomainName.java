package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import org.springframework.stereotype.Component;

/**
 * Single construction point for a Cluster public domain name.
 * <p>
 * The left-most label is the Cluster's persisted DNS label (derived from its
 * display name); the suffix is environment-driven:
 * <ul>
 *   <li>non-production ({@code tapstate.cloud.iac.environment != prod}),
 *       e.g. awsdev: {@code <label>.dev.cloud.<godaddyDomain>}</li>
 *   <li>production: {@code <label>.cloud.<godaddyDomain>}</li>
 * </ul>
 * Every DNS record, health/DNS probe, Nginx/TLS host and resolve JWT audience
 * must take the domain from here so the rule cannot drift between call sites.
 */
@Component
public class ClusterDomainName {

    /** Environment value that places Clusters directly under the apex domain. */
    public static final String PRODUCTION_ENVIRONMENT = "prod";

    private final IacProperties properties;

    public ClusterDomainName(IacProperties properties) {
        this.properties = properties;
    }

    /** Build the fully qualified Cluster domain from a persisted DNS label. */
    public String fqdn(String dnsLabel) {
        String rootDomain = properties.getGodaddy().getDomain();
        if (PRODUCTION_ENVIRONMENT.equalsIgnoreCase(properties.getEnvironment())) {
            return dnsLabel + ".cloud." + rootDomain;
        }
        return dnsLabel + ".dev.cloud." + rootDomain;
    }

    /** Build the fully qualified domain of a persisted Cluster. */
    public String fqdn(Cluster cluster) {
        return fqdn(cluster.getDnsLabel());
    }
}
