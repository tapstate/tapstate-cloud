package io.tapstate.cloud.iac.config;

import io.tapstate.cloud.cluster.Cluster;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClusterRuntimeConfigs#dockerComposeYaml} — verifies the
 * generated compose file wires the per-allocation Atlas URI into the runtime
 * container's {@code TAPSTATE_CLOUD_ATLAS_URI} environment variable, and falls
 * back to a local MongoDB sidecar when no Atlas URI is available.
 */
class ClusterRuntimeConfigsTest {

    private static final String CLUSTER_HEX = "6475a2b3c1d4e5f6a7b8c9d1";
    private static final String STATIC_TOKEN = "sdk-static-token-xyz";

    private final ClusterRuntimeConfigs configs = new ClusterRuntimeConfigs();

    private Cluster cluster() {
        Cluster cluster = Cluster.builder().build();
        cluster.setId(new ObjectId(CLUSTER_HEX));
        cluster.setSdkStaticToken(STATIC_TOKEN);
        return cluster;
    }

    @Test
    void atlasUriIsInjectedIntoRuntimeEnvironmentAndSidecarIsAbsent() {
        String atlasUri = "mongodb+srv://owner_mdb_x:secret@tapstate-shared.mongodb.net"
            + "/mdb_" + CLUSTER_HEX + "?authSource=admin";

        String yaml = configs.dockerComposeYaml(cluster(), "https://cloud.tapstate.com", atlasUri);

        assertTrue(yaml.contains("TAPSTATE_CLOUD_ATLAS_URI: \"" + atlasUri + "\""),
            "compose must inject the per-allocation Atlas URI:\n" + yaml);
        assertFalse(yaml.contains("container_name: tapstate-mongo"),
            "no MongoDB sidecar may be rendered when an Atlas URI is provided:\n" + yaml);
        assertFalse(yaml.contains("image: mongo:7"),
            "the sidecar mongo image must be absent:\n" + yaml);
        assertFalse(yaml.contains("depends_on:"),
            "the sidecar dependency block must be absent:\n" + yaml);
        // Identity wiring remains intact regardless of the database choice.
        assertTrue(yaml.contains("TAPSTATE_CLOUD_CLUSTER_ID: \"" + CLUSTER_HEX + "\""));
        assertTrue(yaml.contains("TAPSTATE_CLOUD_TOKEN: \"" + STATIC_TOKEN + "\""));
        assertTrue(yaml.contains("127.0.0.1:8080:8080"),
            "runtime must stay bound to loopback:\n" + yaml);
    }

    @Test
    void nginxConfTerminatesTlsWithManagedCertificateAndRedirectsHttp() {
        String domain = "6475a2b3c1d4e5f6a7b8c9d1.dev.cloud.tapstate.com";

        String conf = configs.nginxConf(domain);

        assertTrue(conf.contains("listen 443 ssl"), "site must terminate TLS on 443:\n" + conf);
        assertTrue(conf.contains("ssl_certificate " + ClusterRuntimeConfigs.REMOTE_NGINX_CERT_PATH + ";"),
            "site must reference the managed fullchain path:\n" + conf);
        assertTrue(conf.contains("ssl_certificate_key " + ClusterRuntimeConfigs.REMOTE_NGINX_KEY_PATH + ";"),
            "site must reference the managed key path:\n" + conf);
        assertTrue(conf.contains("server_name " + domain + ";"),
            "site must serve the Cluster domain:\n" + conf);
        assertTrue(conf.contains("return 301 https://"),
            "plain HTTP must redirect to HTTPS:\n" + conf);
        assertTrue(conf.contains("proxy_pass http://127.0.0.1:8080;"),
            "443 server must proxy to the local runtime:\n" + conf);
        assertFalse(conf.contains("acme-challenge"),
            "no ACME challenge location may remain after certbot removal:\n" + conf);
    }

    @Test
    void blankAtlasUriProvisionsLocalSidecarAndPointsRuntimeAtIt() {
        String yaml = configs.dockerComposeYaml(cluster(), "https://cloud.tapstate.com", "  ");

        assertTrue(yaml.contains("tapstate-mongo:"),
            "a blank Atlas URI must render the local MongoDB sidecar:\n" + yaml);
        assertTrue(yaml.contains("image: mongo:7"),
            "sidecar must pin the mongo:7 image:\n" + yaml);
        assertTrue(yaml.contains("TAPSTATE_CLOUD_ATLAS_URI: \"mongodb://tapstate-mongo:27017/tapstate_cluster\""),
            "runtime env must point at the sidecar:\n" + yaml);
        assertTrue(yaml.contains("condition: service_healthy"),
            "runtime must wait for sidecar health:\n" + yaml);
        assertTrue(yaml.contains("tapstate-mongo:/data/db"));
    }

    @Test
    void atlasRuntimeResolvesItsVersionTagBeforeDeployment() {
        Map<?, ?> services = services("mongodb://metadata.example.test/cluster_metadata");
        Map<?, ?> runtime = (Map<?, ?>) services.get("tapstate-runtime");

        assertEquals("always", runtime.get("pull_policy"),
            "A cached runtime tag must not hide newly published connectors.");
        assertFalse(services.containsKey("tapstate-mongo"));
    }

    @Test
    void sidecarDeploymentRefreshesOnlyTheRuntimeImage() {
        Map<?, ?> services = services(null);
        Map<?, ?> runtime = (Map<?, ?>) services.get("tapstate-runtime");
        Map<?, ?> mongo = (Map<?, ?>) services.get("tapstate-mongo");

        assertEquals("always", runtime.get("pull_policy"),
            "The selected runtime version must be resolved even when its tag is cached.");
        assertFalse(mongo.containsKey("pull_policy"),
            "Refreshing the runtime must not change the MongoDB sidecar's image policy.");
    }

    private Map<?, ?> services(String atlasUri) {
        Map<?, ?> compose = new Yaml().load(
            configs.dockerComposeYaml(cluster(), "https://cloud.example.test", atlasUri));
        return (Map<?, ?>) compose.get("services");
    }
}
