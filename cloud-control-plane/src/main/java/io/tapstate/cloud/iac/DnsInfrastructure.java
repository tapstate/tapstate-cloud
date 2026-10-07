package io.tapstate.cloud.iac;

/**
 * DNS infrastructure abstraction — manages the public name that points at a
 * Cluster node.
 * <p>
 * Kept independent of {@link ComputeInfrastructure}: the DNS provider is unrelated
 * to the compute provider (e.g. GoDaddy names in front of AWS, AliCloud or GCP
 * nodes), and both must be composable in any combination.
 */
public interface DnsInfrastructure {

    /**
     * Create or replace the A record for {@code fqdn} with {@code ipv4}.
     *
     * @param fqdn fully qualified domain name
     * @param ipv4 target IPv4 address
     */
    void upsertARecord(String fqdn, String ipv4);

    /**
     * Delete the A record for {@code fqdn}.
     * <p>
     * Idempotent: a record that does not exist is treated as success.
     *
     * @param fqdn fully qualified domain name
     */
    void deleteRecord(String fqdn);
}
