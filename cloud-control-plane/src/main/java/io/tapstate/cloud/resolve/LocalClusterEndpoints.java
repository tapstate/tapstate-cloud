package io.tapstate.cloud.resolve;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
        if (console.equals(controlPlane) || !controlPlane.equals(origin(issuer, false))) {
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
            if (console.equals(publicOrigin) || !publicOrigins.add(publicOrigin)) {
                throw invalid("Console and Cluster public origins must be distinct");
            }
            if (!"localhost".equals(configured.getAudience())) {
                throw invalid("audience must match the literal localhost public host");
            }
            if (callbackOrigin.equals(controlPlane)
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
        return enabled ? endpoint(clusterId).publicOrigin() : null;
    }

    public String audience(String clusterId) {
        return enabled ? endpoint(clusterId).audience() : clusterId + ".dev.cloud.tapstate.com";
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
        Endpoint endpoint = clusters.get(clusterId);
        if (endpoint == null) throw invalid("Cluster has no explicit development endpoint mapping");
        return endpoint;
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
        return new BusinessException(AppErrorCode.BAD_REQUEST, 400, "Invalid development endpoint configuration: " + reason);
    }

    private record Endpoint(String publicOrigin, String audience, String callbackOrigin) {}
}
