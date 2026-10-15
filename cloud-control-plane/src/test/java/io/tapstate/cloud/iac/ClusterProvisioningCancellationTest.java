package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
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
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClusterProvisioningCancellationTest {
    private final ClusterRepository clusters = mock(ClusterRepository.class);
    private final ClusterVersionService versions = mock(ClusterVersionService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final Cluster stale = new Cluster();
    private final Cluster durable = new Cluster();
    private ClusterProvisioningService service;

    @BeforeEach
    void setUp() {
        stale.setId(new ObjectId());
        stale.setState(ClusterState.PROVISIONING);
        stale.setRegion("us-east-1");
        stale.setClusterVersion("1.0.0");
        stale.setSdkStaticToken("old-sdk-token");
        stale.setBootstrapToken("old-bootstrap-token");
        durable.setId(stale.getId());
        durable.setState(ClusterState.TERMINATED);
        when(clusters.findById(stale.getId())).thenReturn(Optional.of(durable));
        when(clusters.save(any(Cluster.class))).thenAnswer(invocation -> {
            Cluster saved = invocation.getArgument(0);
            durable.setState(saved.getState());
            durable.setSdkStaticToken(saved.getSdkStaticToken());
            durable.setBootstrapToken(saved.getBootstrapToken());
            return saved;
        });
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Cluster.class)))
            .thenAnswer(invocation -> {
                Query query = invocation.getArgument(0);
                Object expectedState = query.getQueryObject().get("state");
                return UpdateResult.acknowledged(durable.getState().equals(expectedState) ? 1 : 0, 0L, null);
            });
        ComputeInfrastructure compute = mock(ComputeInfrastructure.class);
        when(compute.provider()).thenReturn(CloudProvider.AWS);
        service = new ClusterProvisioningService(clusters, new IacProperties(), Map.of("aws", compute),
            mock(DnsInfrastructure.class), mock(SshClient.class), mock(SshKeyPairGenerator.class),
            mock(SshKeyCrypto.class), mock(ClusterRuntimeConfigs.class), mock(RuntimeAtlasUriResolver.class),
            mock(DeploymentStepRecorder.class), mock(TlsCertificateService.class), mock(TlsNodeInstaller.class),
            versions, mock(ClusterImageResolver.class), mongo);
    }

    @Test
    void lateFailureDoesNotReviveTerminatedClusterOrRestoreCredentials() {
        assertThat(service.provision(stale).success()).isFalse();
        assertTerminated();
    }

    @Test
    void fallbackVersionPersistenceCannotReviveTerminatedCluster() {
        stale.setClusterVersion(null);
        when(versions.findLastActive()).thenReturn(Optional.of(ClusterVersion.builder().version("1.0.0").build()));
        try {
            service.provision(stale);
        } catch (IacException cancelled) {
            // Aborting an already cancelled operation is permitted.
        }
        assertTerminated();
    }

    private void assertTerminated() {
        assertThat(durable.getState()).isEqualTo(ClusterState.TERMINATED);
        assertThat(durable.getSdkStaticToken()).isNull();
        assertThat(durable.getBootstrapToken()).isNull();
    }
}
