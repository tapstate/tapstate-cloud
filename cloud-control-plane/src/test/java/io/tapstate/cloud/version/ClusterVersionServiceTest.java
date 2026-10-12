package io.tapstate.cloud.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.counter.CounterService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit tests for {@link ClusterVersionService}: listing/newest semantics,
 * subscription-time resolution errors, relaxed release validation and state
 * flips.
 */
class ClusterVersionServiceTest {

    private ClusterVersionRepository repository;
    private CounterService counterService;
    private ClusterVersionService service;

    private ClusterVersion alpha;
    private ClusterVersion stable;
    private ClusterVersion offline;

    @BeforeEach
    void setUp() {
        repository = mock(ClusterVersionRepository.class);
        counterService = mock(CounterService.class);
        service = new ClusterVersionService(repository, counterService);

        alpha = ClusterVersion.builder()
            .version("1.2.0").versionNumber(1L).state(ClusterVersionState.ACTIVE)
            .releaseCode(ClusterReleaseCode.ALPHA).build();
        stable = ClusterVersion.builder()
            .version("1.4.0").versionNumber(3L).state(ClusterVersionState.ACTIVE)
            .releaseCode(ClusterReleaseCode.STABLE).build();
        offline = ClusterVersion.builder()
            .version("1.5.0").versionNumber(4L).state(ClusterVersionState.OFFLINE)
            .releaseCode(ClusterReleaseCode.RC).build();
    }

    @Test
    void listsActiveVersionsNewestFirst() {
        when(repository.findByStateOrderByVersionNumberDesc(ClusterVersionState.ACTIVE))
            .thenReturn(List.of(stable, alpha));

        List<ClusterVersion> versions = service.listActiveVersions();

        assertEquals(List.of("1.4.0", "1.2.0"), versions.stream().map(ClusterVersion::getVersion).toList());
    }

    @Test
    void latestIsTheFirstActiveRowByVersionNumber() {
        when(repository.findFirstByStateOrderByVersionNumberDesc(ClusterVersionState.ACTIVE))
            .thenReturn(Optional.of(stable));

        assertSame(stable, service.findClusterLastVersion());
    }

    @Test
    void missingLatestFails503() {
        when(repository.findFirstByStateOrderByVersionNumberDesc(ClusterVersionState.ACTIVE))
            .thenReturn(Optional.empty());

        BusinessException failure = assertThrows(BusinessException.class, service::findClusterLastVersion);
        assertEquals(503, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_UNAVAILABLE, failure.getErrorCode());
    }

    @Test
    void explicitActiveVersionResolves() {
        when(repository.findByVersion("1.4.0")).thenReturn(Optional.of(stable));

        assertSame(stable, service.findActiveByVersion("1.4.0"));
    }

    @Test
    void unknownExplicitVersionFails404() {
        when(repository.findByVersion("9.9.9")).thenReturn(Optional.empty());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.findActiveByVersion("9.9.9"));
        assertEquals(404, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_NOT_FOUND, failure.getErrorCode());
    }

    @Test
    void delistedExplicitVersionFails409() {
        when(repository.findByVersion("1.5.0")).thenReturn(Optional.of(offline));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.findActiveByVersion("1.5.0"));
        assertEquals(409, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_NOT_ACTIVE, failure.getErrorCode());
    }

    @Test
    void releaseAcceptsRelaxedVersionStringsAndAssignsCounterStateAndDate() {
        ReleaseClusterRequest request = new ReleaseClusterRequest(
            "  v2026.10 ", ClusterReleaseCode.RC, "notes",
            GitInfo.builder().branch("main").commitId("abc123").build());
        when(repository.existsByVersion("v2026.10")).thenReturn(false);
        when(counterService.getNextSeq(ClusterVersionService.COUNTER_NAME)).thenReturn(5L);
        when(repository.save(any(ClusterVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ClusterVersion released = service.releaseCluster(request);

        assertEquals("v2026.10", released.getVersion(), "version must be trimmed");
        assertEquals(5L, released.getVersionNumber());
        assertEquals(ClusterVersionState.ACTIVE, released.getState());
        assertEquals(ClusterReleaseCode.RC, released.getReleaseCode());
        assertEquals("main", released.getGit().getBranch());
        assertEquals("abc123", released.getGit().getCommitId());
        // releaseDate is server-assigned current time.
        assertEquals(java.time.Instant.now().getEpochSecond(),
            released.getReleaseDate().getEpochSecond(), 1L);
        verify(repository).existsByVersion("v2026.10");
    }

    @Test
    void releaseRejectsBlankVersionWith400WithoutTouchingCounter() {
        ReleaseClusterRequest request = new ReleaseClusterRequest(
            "   ", ClusterReleaseCode.STABLE, null,
            GitInfo.builder().branch("main").commitId("abc").build());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.releaseCluster(request));
        assertEquals(400, failure.getHttpStatus());
        verify(counterService, never()).getNextSeq(any());
        verify(repository, never()).save(any());
    }

    @Test
    void releaseRejectsDuplicateVersionWith409() {
        ReleaseClusterRequest request = new ReleaseClusterRequest(
            "1.4.0", ClusterReleaseCode.STABLE, null,
            GitInfo.builder().branch("main").commitId("abc").build());
        when(repository.existsByVersion("1.4.0")).thenReturn(true);

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.releaseCluster(request));
        assertEquals(409, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_DUPLICATE, failure.getErrorCode());
        verify(counterService, never()).getNextSeq(any());
    }

    @Test
    void releaseMapsConcurrentUniqueIndexViolationTo409() {
        ReleaseClusterRequest request = new ReleaseClusterRequest(
            "1.4.0", ClusterReleaseCode.STABLE, null,
            GitInfo.builder().branch("main").commitId("abc").build());
        when(repository.existsByVersion("1.4.0")).thenReturn(false);
        when(counterService.getNextSeq(ClusterVersionService.COUNTER_NAME)).thenReturn(6L);
        when(repository.save(any(ClusterVersion.class)))
            .thenThrow(new DuplicateKeyException("version dup"));

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.releaseCluster(request));
        assertEquals(409, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_DUPLICATE, failure.getErrorCode());
    }

    @Test
    void updateStateFlipsAndPersists() {
        when(repository.findByVersion("1.4.0")).thenReturn(Optional.of(stable));
        when(repository.save(any(ClusterVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        ClusterVersion updated = service.updateState("1.4.0", ClusterVersionState.OFFLINE);

        assertEquals(ClusterVersionState.OFFLINE, updated.getState());
        verify(repository).save(stable);
    }

    @Test
    void updateStateUnknownVersionFails404() {
        when(repository.findByVersion("9.9.9")).thenReturn(Optional.empty());

        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.updateState("9.9.9", ClusterVersionState.ACTIVE));
        assertEquals(404, failure.getHttpStatus());
        assertSame(AppErrorCode.CLUSTER_VERSION_NOT_FOUND, failure.getErrorCode());
    }
}
