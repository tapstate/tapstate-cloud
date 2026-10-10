package io.tapstate.cloud.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.counter.CounterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ClusterVersionSeeder} count-guarded bootstrap. */
class ClusterVersionSeederTest {

    private final ClusterVersionRepository repository = mock(ClusterVersionRepository.class);
    private final CounterService counterService = mock(CounterService.class);
    private final ClusterVersionSeeder seeder =
        new ClusterVersionSeeder(repository, counterService);

    @Test
    void seeds060StableActiveFromCounterWhenCatalogEmpty() {
        when(repository.count()).thenReturn(0L);
        when(counterService.getNextSeq(ClusterVersionService.COUNTER_NAME)).thenReturn(1L);

        seeder.seedBootstrapVersion();

        ArgumentCaptor<ClusterVersion> captor = ArgumentCaptor.forClass(ClusterVersion.class);
        verify(repository).save(captor.capture());
        ClusterVersion seeded = captor.getValue();
        assertEquals(ClusterVersionSeeder.BOOTSTRAP_VERSION, seeded.getVersion());
        assertEquals(1L, seeded.getVersionNumber());
        assertEquals(ClusterReleaseCode.STABLE, seeded.getReleaseCode());
        assertEquals(ClusterVersionState.ACTIVE, seeded.getState());
        assertEquals("main", seeded.getGit().getBranch());
        assertEquals("0.6.0", seeded.getGit().getCommitId());
    }

    @Test
    void noopWhenCatalogAlreadyHasVersions() {
        when(repository.count()).thenReturn(3L);

        seeder.seedBootstrapVersion();

        verify(repository, never()).save(any());
        verify(counterService, never()).getNextSeq(any());
    }
}
