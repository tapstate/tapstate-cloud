package io.tapstate.cloud.resolve;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit deployment inputs for development-only Cluster endpoints. */
@Component
@ConfigurationProperties(prefix = "tapstate.cloud.local-development")
@Getter
@Setter
public class LocalClusterEndpointProperties {
    private boolean enabled;
    private String consoleOrigin;
    private List<String> trustedInternalOrigins = List.of();
    private Map<String, Endpoint> clusters = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Endpoint {
        private String publicOrigin;
        private String audience;
        private String callbackOrigin;
    }
}
