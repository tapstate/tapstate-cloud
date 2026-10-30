package io.tapstate.cloud.iac.config;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.IacProperties;
import io.tapstate.cloud.mdb.MdbAllocation;
import io.tapstate.cloud.mdb.MdbAllocationRepository;
import io.tapstate.cloud.mdb.MdbAllocationStatus;
import io.tapstate.cloud.mdb.MdbCluster;
import io.tapstate.cloud.mdb.MdbClusterRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link RuntimeAtlasUriResolver} — the bridge that makes the
 * Cluster runtime wait for its parallel Atlas allocation and renders the
 * per-allocation connection URI for the compose file.
 */
@ExtendWith(MockitoExtension.class)
class RuntimeAtlasUriResolverTest {

    @Mock
    private MdbAllocationRepository allocationRepository;
    @Mock
    private MdbClusterRepository mdbClusterRepository;

    private IacProperties properties;
    private RuntimeAtlasUriResolver resolver;

    private ObjectId allocationId;
    private ObjectId mdbClusterId;

    @BeforeEach
    void setUp() {
        properties = new IacProperties();
        properties.setRuntimeAtlasUri("mongodb+srv://global-fallback.example");
        resolver = new RuntimeAtlasUriResolver(allocationRepository, mdbClusterRepository,
            properties, false);
        allocationId = new ObjectId();
        mdbClusterId = new ObjectId();
    }

    private Cluster clusterWithAllocation() {
        Cluster cluster = Cluster.builder().build();
        cluster.setId(new ObjectId());
        cluster.setMdbAllocationId(allocationId);
        return cluster;
    }

    private MdbAllocation readyAllocation() {
        return MdbAllocation.builder()
            .id(allocationId)
            .mdbClusterId(mdbClusterId)
            .databaseName("mdb_" + clusterHex())
            .dbUsername("owner_mdb_" + clusterHex())
            .dbPassword("pw")
            .status(MdbAllocationStatus.READY)
            .build();
    }

    private String clusterHex() {
        // Allocation db naming is not under test here; keep the hex stable.
        return "6475a2b3c1d4e5f6a7b8c9d1";
    }

    @Test
    void readyAllocationProducesPerAllocationUri() {
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(readyAllocation()));
        when(mdbClusterRepository.findById(mdbClusterId)).thenReturn(Optional.of(
            MdbCluster.builder()
                .id(mdbClusterId)
                .connectionString("mongodb+srv://tapstate-shared.mongodb.net")
                .build()));

        String uri = resolver.resolve(clusterWithAllocation());

        assertEquals(
            "mongodb+srv://owner_mdb_" + clusterHex() + ":pw@tapstate-shared.mongodb.net/"
                + "mdb_" + clusterHex() + "?authSource=admin",
            uri);
        assertEquals(uri, resolver.resolveReady(clusterWithAllocation()));
    }

    @Test
    void failedAllocationFailsWithCodedMdbNotReady() {
        MdbAllocation failed = MdbAllocation.builder()
            .id(allocationId)
            .mdbClusterId(mdbClusterId)
            .status(MdbAllocationStatus.PROVISIONING_FAILED)
            .provisionErrorDetail("Atlas said no")
            .build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(failed));

        IacException ex = assertThrows(IacException.class,
            () -> resolver.resolve(clusterWithAllocation()));
        assertEquals(AppErrorCode.IAC_MDB_NOT_READY, ex.errorCode());
    }

    @ParameterizedTest
    @EnumSource(value = MdbAllocationStatus.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void initializationRejectsEveryNonReadyAllocationWithoutPolling(MdbAllocationStatus status) {
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(
            MdbAllocation.builder().id(allocationId).status(status).build()));

        assertEquals(AppErrorCode.IAC_MDB_NOT_READY,
            assertThrows(IacException.class, () -> resolver.resolveReady(clusterWithAllocation())).errorCode());
        verify(allocationRepository).findById(allocationId);
        verifyNoInteractions(mdbClusterRepository);
    }

    @Test
    void initializationRejectsMissingAllocationWithoutPolling() {
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.empty());

        assertEquals(AppErrorCode.IAC_MDB_NOT_READY,
            assertThrows(IacException.class, () -> resolver.resolveReady(clusterWithAllocation())).errorCode());
        verify(allocationRepository).findById(allocationId);
        verifyNoInteractions(mdbClusterRepository);
    }

    @Test
    void provisioningStillWaitsForTheLinkedAllocation() {
        when(allocationRepository.findById(allocationId)).thenReturn(
            Optional.of(MdbAllocation.builder().id(allocationId).status(MdbAllocationStatus.PROVISIONING).build()),
            Optional.of(readyAllocation()));
        when(mdbClusterRepository.findById(mdbClusterId)).thenReturn(Optional.of(
            MdbCluster.builder().id(mdbClusterId).connectionString("mongodb+srv://tapstate-shared.mongodb.net").build()));

        assertEquals("mongodb+srv://owner_mdb_" + clusterHex() + ":pw@tapstate-shared.mongodb.net/"
            + "mdb_" + clusterHex() + "?authSource=admin", resolver.resolve(clusterWithAllocation()));
        verify(allocationRepository, times(2)).findById(allocationId);
    }

    @Test
    void deletedAllocationIsRejected() {
        MdbAllocation deleted = MdbAllocation.builder()
            .id(allocationId)
            .mdbClusterId(mdbClusterId)
            .status(MdbAllocationStatus.DELETED)
            .build();
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(deleted));

        assertEquals(AppErrorCode.IAC_MDB_NOT_READY,
            assertThrows(IacException.class, () -> resolver.resolve(clusterWithAllocation())).errorCode());
        assertEquals(AppErrorCode.IAC_MDB_NOT_READY,
            assertThrows(IacException.class, () -> resolver.resolveReady(clusterWithAllocation())).errorCode());
    }

    @Test
    void missingParentConnectionStringFailsCoded() {
        when(allocationRepository.findById(allocationId)).thenReturn(Optional.of(readyAllocation()));
        when(mdbClusterRepository.findById(mdbClusterId)).thenReturn(Optional.of(
            MdbCluster.builder().id(mdbClusterId).connectionString(" ").build()));

        assertEquals(AppErrorCode.IAC_MDB_NOT_READY,
            assertThrows(IacException.class, () -> resolver.resolve(clusterWithAllocation())).errorCode());
    }

    @Test
    void clusterWithoutAllocationFallsBackToGlobalUri() {
        Cluster cluster = Cluster.builder().build();
        cluster.setId(new ObjectId());

        assertEquals("mongodb+srv://global-fallback.example", resolver.resolve(cluster));
        assertEquals("mongodb+srv://global-fallback.example", resolver.resolveReady(cluster));
        verifyNoInteractions(allocationRepository, mdbClusterRepository);
    }

    @Test
    void clusterWithoutAllocationAndBlankGlobalUriGetsSidecarBlank() {
        properties.setRuntimeAtlasUri("");
        Cluster cluster = Cluster.builder().build();
        cluster.setId(new ObjectId());

        assertEquals("", resolver.resolve(cluster));
        assertEquals("", resolver.resolveReady(cluster));
        verifyNoInteractions(allocationRepository, mdbClusterRepository);
    }

    @Test
    void linkedAllocationIsIgnoredInAtlasMockModeSoNodeUsesSidecar() {
        // Real compute provisioning with the Atlas allocation engine still in
        // mock mode: the assembled per-allocation credentials do not exist on
        // Atlas, so the node must fall back to the global URI / local sidecar.
        RuntimeAtlasUriResolver mockResolver = new RuntimeAtlasUriResolver(
            allocationRepository, mdbClusterRepository, properties, true);

        assertEquals("mongodb+srv://global-fallback.example",
            mockResolver.resolve(clusterWithAllocation()));
        assertEquals("mongodb+srv://global-fallback.example",
            mockResolver.resolveReady(clusterWithAllocation()));
        verifyNoInteractions(allocationRepository, mdbClusterRepository);
    }

    @Test
    void mockModeWithBlankGlobalUriSelectsSidecar() {
        properties.setRuntimeAtlasUri("");
        RuntimeAtlasUriResolver mockResolver = new RuntimeAtlasUriResolver(
            allocationRepository, mdbClusterRepository, properties, true);

        assertEquals("", mockResolver.resolve(clusterWithAllocation()));
        assertEquals("", mockResolver.resolveReady(clusterWithAllocation()));
        verifyNoInteractions(allocationRepository, mdbClusterRepository);
    }
}
