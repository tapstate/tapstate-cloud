package io.tapstate.cloud.iac.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.tapstate.cloud.iac.IacProperties;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ClusterImageResolver} image reference assembly. */
class ClusterImageResolverTest {

    @Test
    void joinsConfiguredRegistryRepositoryAndVersionTag() {
        ClusterImageResolver resolver = new ClusterImageResolver(new IacProperties());

        // The zero-configuration defaults must render exactly the legacy image
        // coordinates with the requested version as the tag.
        assertEquals("ghcr.io/tapstate/tapstate-cloud:0.6.0",
            resolver.runtimeImage("0.6.0"));
        assertEquals("ghcr.io/tapstate/tapstate-cloud:1.4.0-rc.1",
            resolver.runtimeImage("1.4.0-rc.1"));
    }

    @Test
    void honorsCustomRegistryAndRepository() {
        IacProperties properties = new IacProperties();
        properties.getRuntimeImage().setRegistry("registry.example.com");
        properties.getRuntimeImage().setRepository("mirror/tapstate-cloud");
        ClusterImageResolver resolver = new ClusterImageResolver(properties);

        assertEquals("registry.example.com/mirror/tapstate-cloud:v2026.10",
            resolver.runtimeImage("v2026.10"));
    }
}
