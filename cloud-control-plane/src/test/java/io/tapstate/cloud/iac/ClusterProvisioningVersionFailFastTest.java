package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ProvisionErrorCode;
import io.tapstate.cloud.iac.config.ClusterImageResolver;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.config.RuntimeAtlasUriResolver;
import io.tapstate.cloud.iac.crypto.SshKeyCrypto;
import io.tapstate.cloud.iac.ssh.SshClient;
import io.tapstate.cloud.iac.ssh.SshKeyPairGenerator;
import io.tapstate.cloud.progress.DeploymentStepRecorder;
import io.tapstate.cloud.subscription.SpecConfigService;
import io.tapstate.cloud.tls.TlsCertificateService;
import io.tapstate.cloud.tls.TlsNodeInstaller;
import io.tapstate.cloud.version.ClusterVersion;
import io.tapstate.cloud.version.ClusterVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Version resolution is the first action of provisioning, before any
 * billable cloud resource is touched.
 */
@ExtendWith(MockitoExtension.class)
class ClusterProvisioningVersionFailFastTest {

    private @Mock ClusterRepository clusterRepository;
    private @Mock ClusterDnsRecordCoordinator dnsRecords;
    private @Mock SshClient sshClient;
    private @Mock SshKeyPairGenerator keyPairGenerator;
    private @Mock SshKeyCrypto keyCrypto;
    private @Mock ClusterRuntimeConfigs configs;
    private @Mock RuntimeAtlasUriResolver runtimeAtlasUriResolver;
    private @Mock DeploymentStepRecorder recorder;
    private @Mock TlsCertificateService tlsCertificateService;
    private @Mock TlsNodeInstaller tlsInstaller;
    private @Mock ClusterVersionService clusterVersionService;
    private @Mock ClusterImageResolver imageResolver;
    private @Mock ComputeInfrastructure compute;
    private @Mock MongoTemplate mongo;

    private ClusterProvisioningService service;

    @BeforeEach
    void setUp() {
        when(compute.provider()).thenReturn(CloudProvider.AWS);
        lenient().when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Cluster.class)))
            .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        service = new ClusterProvisioningService(
            clusterRepository,
            new IacProperties(),
            new ClusterDomainName(new IacProperties()),
            Map.of("aws", compute),
            dnsRecords,
            sshClient,
            keyPairGenerator,
            keyCrypto,
            configs,
            runtimeAtlasUriResolver,
            recorder,
            tlsCertificateService,
            tlsInstaller,
            clusterVersionService,
            imageResolver,
            mongo,
            org.mockito.Mockito.mock(SpecConfigService.class));
    }

    @Test
    void emptyCatalogFailsProvisioningBeforeAnyCloudResource() {
        Cluster cluster = new Cluster();
        cluster.setId(new org.bson.types.ObjectId());
        cluster.setDnsLabel("version-fail-fast");
        cluster.setClusterVersion("  ");
        when(clusterVersionService.findLastActive()).thenReturn(Optional.empty());

        ClusterProvisioningService.ProvisionResult result = service.provision(cluster);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ProvisionErrorCode.CLUSTER_VERSION_UNAVAILABLE);

        ArgumentCaptor<Update> saved = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), saved.capture(), eq(Cluster.class));
        assertThat(saved.getValue().getUpdateObject().get("$set", org.bson.Document.class))
            .containsEntry("provisionErrorCode", ProvisionErrorCode.CLUSTER_VERSION_UNAVAILABLE);

        // No image resolution, no TLS lookup, no compute/SSH/DNS work: the
        // failure happens before a billable EC2 node could be created.
        // (compute.provider() is the constructor-time bean selection call.)
        verify(compute).provider();
        verifyNoMoreInteractions(compute);
        verifyNoInteractions(imageResolver, tlsCertificateService, dnsRecords, sshClient);
    }

    @Test
    void blankPinnedVersionFallsBackToLatestActiveAndIsPersistedBeforeProvisioningContinues() {
        Cluster cluster = new Cluster();
        cluster.setId(new org.bson.types.ObjectId());
        cluster.setDnsLabel("version-fail-fast");
        ClusterVersion latest = ClusterVersion.builder().version("9.9.9").build();
        when(clusterVersionService.findLastActive()).thenReturn(Optional.of(latest));
        when(imageResolver.runtimeImage("9.9.9")).thenReturn("registry/repo:9.9.9");
        // The version fallback must resolve before the TLS fail-fast gate,
        // so an absent certificate is the next observable failure.
        when(tlsCertificateService.findValidForHost(anyString())).thenReturn(Optional.empty());

        ClusterProvisioningService.ProvisionResult result = service.provision(cluster);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ProvisionErrorCode.TLS_CERTIFICATE_UNAVAILABLE);
        assertThat(cluster.getClusterVersion()).isEqualTo("9.9.9");

        ArgumentCaptor<Update> saved = ArgumentCaptor.forClass(Update.class);
        verify(mongo, org.mockito.Mockito.atLeastOnce()).updateFirst(any(Query.class), saved.capture(), eq(Cluster.class));
        assertThat(saved.getAllValues()).anySatisfy(update ->
            assertThat(update.getUpdateObject().get("$set", org.bson.Document.class))
                .containsEntry("clusterVersion", "9.9.9"));
        verify(imageResolver).runtimeImage("9.9.9");
        // The absent certificate stops provisioning before compute is touched
        // (apart from constructor-time bean selection).
        verify(compute).provider();
        verifyNoMoreInteractions(compute);
    }
}
