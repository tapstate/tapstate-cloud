package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.iac.config.ClusterImageResolver;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.config.RuntimeAtlasUriResolver;
import io.tapstate.cloud.iac.crypto.SshKeyCrypto;
import io.tapstate.cloud.iac.ssh.SshClient;
import io.tapstate.cloud.iac.ssh.SshKeyPairGenerator;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.SpecConfig;
import io.tapstate.cloud.subscription.SpecConfigService;
import io.tapstate.cloud.tls.TlsCertificateService;
import io.tapstate.cloud.tls.TlsNodeInstaller;
import io.tapstate.cloud.version.ClusterVersionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which EC2 type a Cluster gets.
 * <p>
 * The size the customer picked must decide the machine: a 4C16G subscription left
 * on the environment default would be a 2-vCPU instance sold as 4C16G. The default
 * survives only for Clusters whose spec predates the catalogue carrying a provider
 * type — never as a silent fallback for a spec that does carry one.
 */
class ClusterInstanceTypeTest {

    private final SpecConfigService specConfigService = mock(SpecConfigService.class);
    private ClusterProvisioningService service;

    @BeforeEach
    void setUp() {
        IacProperties properties = new IacProperties();
        properties.getAws().setInstanceType("t3.medium");
        ComputeInfrastructure compute = mock(ComputeInfrastructure.class);
        when(compute.provider()).thenReturn(CloudProvider.AWS);
        service = new ClusterProvisioningService(
            mock(ClusterRepository.class),
            properties,
            new ClusterDomainName(properties),
            Map.of("aws", compute),
            mock(ClusterDnsRecordCoordinator.class),
            mock(SshClient.class),
            mock(SshKeyPairGenerator.class),
            mock(SshKeyCrypto.class),
            mock(ClusterRuntimeConfigs.class),
            mock(RuntimeAtlasUriResolver.class),
            mock(DeploymentStepRecorder.class),
            mock(TlsCertificateService.class),
            mock(TlsNodeInstaller.class),
            mock(ClusterVersionService.class),
            mock(ClusterImageResolver.class),
            mock(MongoTemplate.class),
            specConfigService);
    }

    @Test
    @DisplayName("the purchased spec decides the EC2 type")
    void instanceTypeComesFromThePurchasedSpec() {
        stubSpec("2c8g", "t2.large");
        stubSpec("4c16g", "t2.xlarge");
        stubSpec("8c32g", "t2.2xlarge");

        assertThat(service.instanceTypeFor(clusterWithSpec("2c8g"))).isEqualTo("t2.large");
        assertThat(service.instanceTypeFor(clusterWithSpec("4c16g"))).isEqualTo("t2.xlarge");
        assertThat(service.instanceTypeFor(clusterWithSpec("8c32g"))).isEqualTo("t2.2xlarge");
    }

    @Test
    @DisplayName("a spec without a provider type falls back to the environment default")
    void specWithoutProviderTypeFallsBackToTheDefault() {
        stubSpec("basic", "");

        assertThat(service.instanceTypeFor(clusterWithSpec("basic"))).isEqualTo("t3.medium");
    }

    @Test
    @DisplayName("an unknown spec key falls back to the environment default")
    void unknownSpecFallsBackToTheDefault() {
        when(specConfigService.findByKey("gone")).thenReturn(Optional.empty());

        assertThat(service.instanceTypeFor(clusterWithSpec("gone"))).isEqualTo("t3.medium");
    }

    @Test
    @DisplayName("the shipped catalogue maps every sold spec to an EC2 type")
    void shippedCatalogueMapsEverySpec() {
        assertThat(catalogue().stream().collect(java.util.stream.Collectors.toMap(
                entry -> entry.get("key"), entry -> entry.get("awsEc2Spec"))))
            .containsEntry("2c8g", "t2.large")
            .containsEntry("4c16g", "t2.xlarge")
            .containsEntry("8c32g", "t2.2xlarge");
    }

    // ---- helpers ---------------------------------------------------------

    private void stubSpec(String key, String ec2Type) {
        when(specConfigService.findByKey(key)).thenReturn(Optional.of(
            SpecConfig.builder().key(key).awsEc2Spec(ec2Type).enabled(true).build()));
    }

    private static Cluster clusterWithSpec(String specKey) {
        return Cluster.builder().id(new ObjectId()).specKey(specKey).build();
    }

    private static List<Map<String, Object>> catalogue() {
        try (InputStream in = ClusterInstanceTypeTest.class.getResourceAsStream("/spec-config.json")) {
            assertThat(in).as("classpath:spec-config.json").isNotNull();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonMapper.builder().build()
                .readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new AssertionError("cannot read spec-config.json", e);
        }
    }
}
