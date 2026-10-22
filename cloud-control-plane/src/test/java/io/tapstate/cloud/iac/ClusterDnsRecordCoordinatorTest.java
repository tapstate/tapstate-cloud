package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClusterDnsRecordCoordinatorTest {

    private final LockProvider lockProvider = mock(LockProvider.class);
    private final ClusterRepository clusters = mock(ClusterRepository.class);
    private final DnsInfrastructure dns = mock(DnsInfrastructure.class);
    private final SimpleLock lock = mock(SimpleLock.class);
    private ClusterDnsRecordCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
        coordinator = new ClusterDnsRecordCoordinator(lockProvider, clusters, dns,
            new ClusterDomainName(new IacProperties()));
    }

    @Test
    void publishPersistsTheOwnerBeforeReleasingTheHostnameLock() {
        ObjectId ownerId = new ObjectId();
        Cluster owner = new Cluster();
        owner.setId(ownerId);
        owner.setState(ClusterState.READY);
        when(clusters.findById(ownerId)).thenReturn(Optional.of(owner));
        AtomicBoolean ownerPersisted = new AtomicBoolean();
        var operations = new ArrayList<String>();
        doAnswer(invocation -> {
            operations.add("publish");
            return null;
        }).when(dns).upsertARecord(anyString(), anyString());

        coordinator.publish("feynman.dev.cloud.tapstate.com", "203.0.113.42",
            ownerId, () -> {
                ownerPersisted.set(true);
                operations.add("persist");
            });

        verify(dns).upsertARecord("feynman.dev.cloud.tapstate.com", "203.0.113.42");
        assertThat(ownerPersisted).isTrue();
        assertThat(operations).containsExactly("persist", "publish");
        verify(lock).unlock();
    }

    @Test
    void deleteFindsTheHostnameFromThePersistedDnsLabelWhenDnsFieldsWereNotSaved() {
        Cluster terminated = new Cluster();
        terminated.setId(new ObjectId());
        terminated.setDnsLabel("feynman");

        assertThat(coordinator.recordName(terminated)).isEqualTo("feynman.dev.cloud.tapstate.com");
        assertThat(coordinator.deleteIfUnowned(terminated)).isTrue();

        verify(dns).deleteRecord("feynman.dev.cloud.tapstate.com");
    }

    @Test
    void deletePreservesAHostnameOwnedByAnotherLiveCluster() {
        ObjectId terminatedClusterId = new ObjectId();
        when(clusters.existsByPublicDnsAndStateNotAndIdNot(
            "feynman.dev.cloud.tapstate.com", ClusterState.TERMINATED, terminatedClusterId))
            .thenReturn(true);

        assertThat(coordinator.deleteIfUnowned("feynman.dev.cloud.tapstate.com", terminatedClusterId))
            .isTrue();

        verify(dns, never()).deleteRecord(any());
        verify(lock).unlock();
    }

    @Test
    void deleteReleasesARecordWhenNoLiveClusterOwnsIt() {
        ObjectId terminatedClusterId = new ObjectId();

        assertThat(coordinator.deleteIfUnowned("feynman.dev.cloud.tapstate.com", terminatedClusterId))
            .isTrue();

        verify(clusters).existsByPublicDnsAndStateNotAndIdNot(
            "feynman.dev.cloud.tapstate.com", ClusterState.TERMINATED, terminatedClusterId);
        verify(dns).deleteRecord("feynman.dev.cloud.tapstate.com");
    }

    @Test
    void busyHostnameLockDefersDeleteAndFailsPublication() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        assertThat(coordinator.deleteIfUnowned("feynman.dev.cloud.tapstate.com", new ObjectId()))
            .isFalse();
        assertThatThrownBy(() -> coordinator.publish(
            "feynman.dev.cloud.tapstate.com", "203.0.113.42", new ObjectId(), () -> {}))
            .isInstanceOf(IacException.class);
    }

    @Test
    void terminatedClusterCannotPublishAfterItsHostnameHasBeenReused() {
        ObjectId oldClusterId = new ObjectId();
        Cluster terminated = new Cluster();
        terminated.setId(oldClusterId);
        terminated.setState(ClusterState.TERMINATED);
        when(clusters.findById(oldClusterId)).thenReturn(Optional.of(terminated));

        assertThatThrownBy(() -> coordinator.publish(
            "feynman.dev.cloud.tapstate.com", "203.0.113.1", oldClusterId, () -> {}))
            .isInstanceOf(IacException.class);

        verify(dns, never()).upsertARecord(any(), any());
        verify(lock).unlock();
    }
}
