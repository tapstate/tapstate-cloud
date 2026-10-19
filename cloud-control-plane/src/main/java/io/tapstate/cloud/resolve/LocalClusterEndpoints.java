package io.tapstate.cloud.resolve;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.common.api.MessageUtils;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** Resolves trusted development endpoints without reading request headers or callback URLs. */
@Service
public class LocalClusterEndpoints {
    private final boolean enabled;
    private final Map<String, Endpoint> clusters;

    public LocalClusterEndpoints(LocalClusterEndpointProperties properties,
                                 Environment environment,
                                 @Value("${tapstate.cloud.issuer:https://cloud.tapstate.io}") String issuer) {
        enabled = properties.isEnabled();
        if (!enabled) {
            clusters = Map.of();
            return;
        }
        if (!environment.acceptsProfiles(Profiles.of("local-development"))) {
            throw invalid("the local-development profile is required");
        }
        String console = origin(properties.getConsoleOrigin(), true);
        String controlPlane = origin(properties.getControlPlaneOrigin(), false);
        if (sameSocket(console, controlPlane) || !controlPlane.equals(origin(issuer, false))) {
            throw invalid("issuer must equal the dedicated internal Control Plane origin");
        }
        if (properties.getTrustedInternalOrigins() == null || properties.getClusters() == null) {
            throw invalid("trusted origins and Cluster mappings are required");
        }
        Set<String> trusted = new HashSet<>();
        for (String value : properties.getTrustedInternalOrigins()) {
            trusted.add(origin(value, false));
        }
        if (!trusted.contains(controlPlane)) {
            throw invalid("issuer must be a trusted internal origin matching the SDK base URL");
        }
        Map<String, Endpoint> validated = new LinkedHashMap<>();
        Set<String> publicOrigins = new HashSet<>();
        for (var entry : properties.getClusters().entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[0-9a-f]{24}") || entry.getValue() == null) {
                throw invalid("Cluster mappings require a canonical Cluster ID");
            }
            var configured = entry.getValue();
            String publicOrigin = origin(configured.getPublicOrigin(), true);
            String callbackOrigin = origin(configured.getCallbackOrigin(), false);
            if (sameSocket(console, publicOrigin) || sameSocket(controlPlane, publicOrigin)
                || !publicOrigins.add(publicOrigin)) {
                throw invalid("Console, Control Plane and Cluster public origins must be distinct");
            }
            if (!"localhost".equals(configured.getAudience())) {
                throw invalid("audience must match the literal localhost public host");
            }
            if (sameSocket(callbackOrigin, controlPlane) || sameSocket(callbackOrigin, console)
                || sameSocket(callbackOrigin, publicOrigin)
                || URI.create(callbackOrigin).getPort() != URI.create(publicOrigin).getPort()) {
                throw invalid("callback must target the Cluster role at its selected public/backend port");
            }
            if (!trusted.contains(callbackOrigin)) {
                throw invalid("callback must be a trusted internal deployment origin");
            }
            validated.put(entry.getKey(), new Endpoint(publicOrigin, "localhost", callbackOrigin));
        }
        if (validated.isEmpty()) {
            throw invalid("at least one explicit Cluster mapping is required");
        }
        clusters = Map.copyOf(validated);
    }

    public String publicOrigin(Cluster cluster) {
        if (enabled) return endpoint(cluster.getId().toHexString()).publicOrigin();
        return cluster.getPublicDns() != null ? "https://" + cluster.getPublicDns() : "http://localhost:30080";
    }

    String exchangeOrigin(String clusterId) {
        if (!enabled) return null;
        Endpoint endpoint = clusters.get(new ObjectId(clusterId).toHexString());
        if (endpoint == null) throw new BusinessException(AppErrorCode.EXCHANGE_CODE_CLUSTER_MISMATCH, 401);
        return endpoint.publicOrigin();
    }

    public String audience(String clusterId) {
        if (!enabled) throw new IllegalStateException("Development audience lookup requires enabled local endpoints");
        return endpoint(clusterId).audience();
    }

    public String callbackOrigin(JtiRecord record) {
        if (!enabled) return record.getClusterBaseUri();
        Endpoint endpoint = endpoint(record.getClusterId().toHexString());
        if (!endpoint.publicOrigin().equals(record.getClusterBaseUri())) {
            throw invalid("revocation record does not match its configured Cluster public origin");
        }
        return endpoint.callbackOrigin();
    }

    private Endpoint endpoint(String clusterId) {
        Endpoint endpoint = clusters.get(new ObjectId(clusterId).toHexString());
        if (endpoint == null) throw invalid("Cluster has no explicit development endpoint mapping");
        return endpoint;
    }

    private static boolean sameSocket(String first, String second) {
        URI left = URI.create(first);
        URI right = URI.create(second);
        // HTTP and HTTPS cannot assign different roles to the same listening socket.
        return left.getPort() == right.getPort() && socketHost(left).equals(socketHost(right));
    }

    private static String socketHost(URI origin) {
        String host = origin.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if ("localhost".equals(host) || host.matches("127(?:\\.\\d{1,3}){3}")) return "loopback";
        if (host.matches("[0-9]+")) {
            try {
                long address = Long.parseLong(host);
                if (address > 0xffffffffL) throw invalid("numeric IPv4 origin is invalid");
                byte[] bytes = {(byte) (address >>> 24), (byte) (address >>> 16),
                    (byte) (address >>> 8), (byte) address};
                InetAddress literal = InetAddress.getByAddress(bytes);
                return literal.isLoopbackAddress() ? "loopback" : literal.getHostAddress();
            } catch (NumberFormatException | UnknownHostException exception) {
                throw invalid("numeric IPv4 origin is invalid");
            }
        }
        if (host.startsWith("[")) {
            try {
                // Only numeric IPv6 literals reach this branch; never resolve deployment DNS names.
                InetAddress address = InetAddress.getByName(host);
                return address.isLoopbackAddress() ? "loopback" : address.getHostAddress();
            } catch (UnknownHostException exception) {
                throw invalid("internal IPv6 origin is invalid");
            }
        }
        return host;
    }

    private static String origin(String value, boolean publicOrigin) {
        try {
            if (value == null || !value.equals(value.trim())) throw invalid("an exact origin is required");
            URI uri = new URI(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"".equals(uri.getRawPath()) || uri.getPort() < 1 || uri.getPort() > 65535
                || !uri.getRawAuthority().equals(uri.getHost() + ":" + uri.getPort())) {
                throw invalid("origins require a host and explicit port, without path, query, credentials or fragment");
            }
            if (publicOrigin && (!"http".equals(uri.getScheme()) || !"localhost".equals(uri.getHost())
                || !uri.getRawAuthority().equals("localhost:" + uri.getPort()))) {
                throw invalid("public origins require literal localhost HTTP with an exact port");
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw invalid("origin syntax is invalid");
        }
    }

    private static BusinessException invalid(String reason) {
        return new ConfigurationException(reason);
    }

    private static final class ConfigurationException extends BusinessException {
        private ConfigurationException(String reason) {
            super(AppErrorCode.BAD_REQUEST, 400, "Invalid development endpoint configuration: " + reason);
        }

        @Override
        public String getMessage() {
            return MessageUtils.getMessage(Locale.ENGLISH, getErrorCode(), getParams());
        }
    }

    private record Endpoint(String publicOrigin, String audience, String callbackOrigin) {}
}
