package io.tapstate.cloud.iac.model;

/**
 * A single inbound firewall rule — provider-independent representation.
 *
 * @param protocol  IP protocol ({@code tcp} / {@code udp})
 * @param fromPort  start of the port range (inclusive)
 * @param toPort    end of the port range (inclusive)
 * @param cidr      source CIDR allowed by this rule (e.g. {@code 0.0.0.0/0})
 */
public record IngressRule(String protocol, int fromPort, int toPort, String cidr) {

    /**
     * Build a single-port rule.
     *
     * @param protocol IP protocol
     * @param port     port number
     * @param cidr     source CIDR
     */
    public static IngressRule of(String protocol, int port, String cidr) {
        return new IngressRule(protocol, port, port, cidr);
    }
}
