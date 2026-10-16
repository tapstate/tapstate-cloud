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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
    private @Mock DnsInfrastructure dns;
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

    private ClusterProvisioningService service;

    @BeforeEach
    void setUp() {
        when(compute.provider()).thenReturn(CloudProvider.AWS);
        service = new ClusterProvisioningService(
            clusterRepository,
            new IacProperties(),
            new ClusterDomainName(new IacProperties()),
            Map.of("aws", compute),
            dns,
            sshClient,
            keyPairGenerator,
            keyCrypto,
            configs,
            runtimeAtlasUriResolver,
            recorder,
            tlsCertificateService,
            tlsInstaller,
            clusterVersionService,
            imageResolver);
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

        ArgumentCaptor<Cluster> saved = ArgumentCaptor.forClass(Cluster.class);
        verify(clusterRepository).save(saved.capture());
        assertThat(saved.getValue().getProvisionErrorCode())
            .isEqualTo(ProvisionErrorCode.CLUSTER_VERSION_UNAVAILABLE);

        // No image resolution, no TLS lookup, no compute/SSH/DNS work: the
        // failure happens before a billable EC2 node could be created.
        // (compute.provider() is the constructor-time bean selection call.)
        verify(compute).provider();
        verifyNoMoreInteractions(compute);
        verifyNoInteractions(imageResolver, tlsCertificateService, dns, sshClient);
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

        ArgumentCaptor<Cluster> saved = ArgumentCaptor.forClass(Cluster.class);
        verify(clusterRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
            .allMatch(document -> "9.9.9".equals(document.getClusterVersion()));
        verify(imageResolver).runtimeImage("9.9.9");
        // The absent certificate stops provisioning before compute is touched
        // (apart from constructor-time bean selection).
        verify(compute).provider();
        verifyNoMoreInteractions(compute);
    }
}
