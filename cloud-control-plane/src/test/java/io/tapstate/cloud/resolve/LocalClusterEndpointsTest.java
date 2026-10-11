package io.tapstate.cloud.resolve;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalClusterEndpointsTest {
    static final String CLUSTER_ID = "0123456789abcdef01234567";
    static final String ISSUER = "http://control-plane:8080";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LocalClusterEndpointProperties.class)
    @Import(LocalClusterEndpoints.class)
    static class EndpointConfiguration {}

    @Test
    void bindsTrustedDeploymentPropertiesBeforeConstructingTheResolver() {
        new ApplicationContextRunner().withUserConfiguration(EndpointConfiguration.class)
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("local-development"))
            .withPropertyValues(
                "tapstate.cloud.issuer=" + ISSUER,
                "tapstate.cloud.local-development.enabled=true",
                "tapstate.cloud.local-development.console-origin=http://localhost:28175",
                "tapstate.cloud.local-development.control-plane-origin=" + ISSUER,
                "tapstate.cloud.local-development.trusted-internal-origins[0]=" + ISSUER,
                "tapstate.cloud.local-development.trusted-internal-origins[1]=http://cluster-1:28443",
                "tapstate.cloud.local-development.clusters." + CLUSTER_ID + ".public-origin=http://localhost:28443",
                "tapstate.cloud.local-development.clusters." + CLUSTER_ID + ".audience=localhost",
                "tapstate.cloud.local-development.clusters." + CLUSTER_ID + ".callback-origin=http://cluster-1:28443")
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals("localhost", context.getBean(LocalClusterEndpoints.class).audience(CLUSTER_ID));
            });
    }

    static LocalClusterEndpointProperties properties(String callback) {
        var properties = new LocalClusterEndpointProperties();
        properties.setEnabled(true);
        properties.setConsoleOrigin("http://localhost:28175");
        properties.setControlPlaneOrigin(ISSUER);
        properties.setTrustedInternalOrigins(List.of(ISSUER, callback));
        var endpoint = new LocalClusterEndpointProperties.Endpoint();
        endpoint.setPublicOrigin("http://localhost:28443");
        endpoint.setAudience("localhost");
        endpoint.setCallbackOrigin(callback);
        properties.setClusters(Map.of(CLUSTER_ID, endpoint));
        return properties;
    }

    static LocalClusterEndpoints local(LocalClusterEndpointProperties properties) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local-development");
        return new LocalClusterEndpoints(properties, environment, ISSUER);
    }

    @Test
    void separatesPublicAudienceAndTrustedCallbackByClusterIdentity() {
        var resolver = local(properties("http://cluster-1:28443"));
        var cluster = Cluster.builder().id(new ObjectId(CLUSTER_ID)).publicDns("ignored.example").build();
        assertEquals("http://localhost:28443", resolver.publicOrigin(cluster));
        assertEquals("localhost", resolver.audience(CLUSTER_ID));
        var record = JtiRecord.builder().clusterId(cluster.getId()).clusterBaseUri("http://localhost:28443").build();
        assertEquals("http://cluster-1:28443", resolver.callbackOrigin(record));
        record.setClusterBaseUri("http://untrusted.example:8080");
        assertThrows(BusinessException.class, () -> resolver.callbackOrigin(record));
        assertThrows(BusinessException.class, () -> resolver.audience("fedcba9876543210fedcba98"));
    }

    @Test
    void disabledConfigurationPreservesOrdinaryEndpointsWithoutApplyingLocalOverrides() {
        var properties = properties("http://cluster-1:28443");
        properties.setEnabled(false);
        var resolver = new LocalClusterEndpoints(properties, new MockEnvironment(), "https://cloud.tapstate.io");
        var cluster = Cluster.builder().id(new ObjectId(CLUSTER_ID)).publicDns("cluster.example").build();
        assertEquals("https://cluster.example", resolver.publicOrigin(cluster));
        assertEquals(CLUSTER_ID + ".dev.cloud.tapstate.com", resolver.audience(CLUSTER_ID));
        var record = JtiRecord.builder().clusterBaseUri("https://cluster.example").build();
        assertEquals("https://cluster.example", resolver.callbackOrigin(record));
    }

    @Test
    void requiresExplicitProfileAndCompleteTrustedConfiguration() {
        var properties = properties("http://cluster-1:28443");
        BusinessException failure = assertThrows(BusinessException.class,
            () -> new LocalClusterEndpoints(properties, new MockEnvironment(), ISSUER));
        assertEquals(AppErrorCode.BAD_REQUEST, failure.getErrorCode());
        properties.setTrustedInternalOrigins(List.of(ISSUER));
        assertThrows(BusinessException.class, () -> local(properties));
        properties.setTrustedInternalOrigins(List.of("http://cluster-1:28443"));
        assertThrows(BusinessException.class, () -> local(properties));
        properties.setClusters(Map.of());
        assertThrows(BusinessException.class, () -> local(properties));
    }

    @Test
    void rejectsRemoteHttpCredentialsNonOriginsAndAudienceMismatch() {
        for (String invalid : List.of("http://remote.example:28443", "http://127.0.0.1:28443",
            "http://localhost:0", "http://localhost:65536", "http://localhost:28443/path",
            "http://user:secret@localhost:28443", "http://localhost:28443?code=x",
            "http://localhost:28443#code", "http://localhost:028443", "http://localhost:28175")) {
            var properties = properties("http://cluster-1:28443");
            properties.getClusters().get(CLUSTER_ID).setPublicOrigin(invalid);
            assertThrows(BusinessException.class, () -> local(properties), invalid);
        }
        var invalidAudience = properties("http://cluster-1:28443");
        invalidAudience.getClusters().get(CLUSTER_ID).setAudience("cluster.example");
        assertThrows(BusinessException.class, () -> local(invalidAudience));
        var invalidConsole = properties("http://cluster-1:28443");
        invalidConsole.setConsoleOrigin("http://remote.example:28175");
        assertThrows(BusinessException.class, () -> local(invalidConsole));
    }

    @Test
    void rejectsIssuerRoleConfusionEvenWhenBothOriginsAreTrusted() {
        var properties = properties("http://cluster-1:28443");
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local-development");
        assertThrows(BusinessException.class,
            () -> new LocalClusterEndpoints(properties, environment, "http://cluster-1:28443"));
    }

    @Test
    void requiresADedicatedControlPlaneOriginRatherThanAGenericTrustList() {
        var missingControlPlane = properties("http://cluster-1:28443");
        missingControlPlane.setControlPlaneOrigin(null);
        assertThrows(BusinessException.class, () -> local(missingControlPlane));
        var wrongControlPlane = properties("http://cluster-1:28443");
        wrongControlPlane.setControlPlaneOrigin("http://cluster-1:28443");
        assertThrows(BusinessException.class, () -> local(wrongControlPlane));
        var roleCollision = properties("http://cluster-1:28443");
        String origin = "http://control-plane:28443";
        roleCollision.setControlPlaneOrigin(origin);
        roleCollision.setTrustedInternalOrigins(List.of(origin));
        roleCollision.getClusters().get(CLUSTER_ID).setCallbackOrigin(origin);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local-development");
        assertThrows(BusinessException.class,
            () -> new LocalClusterEndpoints(roleCollision, environment, origin));
    }

    @Test
    void preservesTrustedIpv6CallbacksAtTheSelectedPort() {
        var resolver = local(properties("http://[fd00::11]:28443"));
        var record = JtiRecord.builder().clusterId(new ObjectId(CLUSTER_ID))
            .clusterBaseUri("http://localhost:28443").build();
        assertEquals("http://[fd00::11]:28443", resolver.callbackOrigin(record));
    }

    @Test
    void requiresTheCallbackToKeepTheSelectedClusterPort() {
        assertThrows(BusinessException.class, () -> local(properties("http://cluster-1:28444")));
        assertThrows(BusinessException.class, () -> local(properties(ISSUER)));
    }

    @Test
    void rejectsNoncanonicalInternalPortsEvenWhenTheInputIsTrusted() {
        assertThrows(BusinessException.class, () -> local(properties("http://cluster-1:028443")));
    }

    @Test
    void incompleteCollectionsProduceCodedConfigurationFailures() {
        var missingOrigins = properties("http://cluster-1:28443");
        missingOrigins.setTrustedInternalOrigins(null);
        assertThrows(BusinessException.class, () -> local(missingOrigins));
        var missingClusters = properties("http://cluster-1:28443");
        missingClusters.setClusters(null);
        assertThrows(BusinessException.class, () -> local(missingClusters));
    }

    @Test
    void allowsOnlyExactInternalOriginsFromTrustedDeploymentInputs() {
        var properties = properties("http://10.0.0.11:28443");
        var resolver = local(properties);
        var record = JtiRecord.builder().clusterId(new ObjectId(CLUSTER_ID))
            .clusterBaseUri("http://localhost:28443").build();
        assertEquals("http://10.0.0.11:28443", resolver.callbackOrigin(record));
        properties.getClusters().get(CLUSTER_ID).setCallbackOrigin("http://10.0.0.11:28444");
        assertThrows(BusinessException.class, () -> local(properties));
    }
}
