package io.tapstate.cloud.cluster;

import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.region.Region;
import io.tapstate.cloud.region.RegionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cluster creation must derive and persist the DNS label from the display name,
 * reject names that yield no valid label, and guard the globally shared DNS
 * namespace across organizations and regions.
 */
class ClusterServiceDnsNameTest {

    private ClusterRepository clusterRepository;
    private ClusterService clusterService;
    private final String orgId = new ObjectId().toHexString();

    @BeforeEach
    void setUp() {
        clusterRepository = mock(ClusterRepository.class);
        ClusterStatusReportHistoryRepository historyRepository =
            mock(ClusterStatusReportHistoryRepository.class);
        MemberRepository memberRepository = mock(MemberRepository.class);
        RegionService regionService = mock(RegionService.class);
        ClusterStateMachineService stateMachineService = mock(ClusterStateMachineService.class);
        clusterService = new ClusterService(
            clusterRepository, historyRepository, memberRepository,
            regionService, stateMachineService, mock(MongoTemplate.class), mock(ClusterPartialWriter.class),
            mock(io.tapstate.cloud.iac.ClusterProvisioningService.class));

        when(memberRepository.existsByOrgIdAndUserEmail(any(), anyString())).thenReturn(true);
        when(regionService.getByCode(anyString())).thenReturn(mock(Region.class));
        when(clusterRepository.existsByOrgIdAndRegionAndNameAndStateNot(
            any(), anyString(), anyString(), any())).thenReturn(false);
        when(clusterRepository.existsByDnsLabelAndStateNot(anyString(), any())).thenReturn(false);
        when(clusterRepository.save(any(Cluster.class))).thenAnswer(invocation -> {
            Cluster cluster = invocation.getArgument(0);
            if (cluster.getId() == null) {
                cluster.setId(new ObjectId());
            }
            return cluster;
        });
    }

    @Test
    void preservesTheDisplayNameAndDerivesADnsLabelWithTheClusterId() {
        Cluster saved = clusterService.createCluster(orgId, new ObjectId(),
            "ap-southeast-1", "My Production Cluster", "basic",
            "user@example.com", "1.0.0");

        assertThat(saved.getName()).isEqualTo("My Production Cluster");
        assertThat(saved.getDnsLabel()).startsWith("my-production-cluster-");
        assertThat(saved.getDnsLabel()).endsWith(saved.getId().toHexString());
    }

    @Test
    void rejectsNameWithoutAnyLetterOrDigit() {
        assertThatThrownBy(() -> clusterService.createCluster(orgId, new ObjectId(),
            "ap-southeast-1", "我的集群", "basic", "user@example.com", "1.0.0"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(AppErrorCode.CLUSTER_NAME_DNS_INVALID);
                assertThat(ex.getHttpStatus()).isEqualTo(400);
            });
    }

    @Test
    void rejectsOverLongNormalizedLabel() {
        String longName = "a".repeat(DnsLabels.MAX_LABEL_LENGTH + 1);

        assertThatThrownBy(() -> clusterService.createCluster(orgId, new ObjectId(),
            "ap-southeast-1", longName, "basic", "user@example.com", "1.0.0"))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(AppErrorCode.CLUSTER_NAME_DNS_INVALID));
    }

    @Test
    void preservesDisplayNameAndUsesUniqueDnsLabelWhenNormalizedNameIsTaken() {
        AtomicInteger dnsLabelChecks = new AtomicInteger();
        when(clusterRepository.existsByDnsLabelAndStateNot(anyString(), any()))
            .thenAnswer(invocation -> dnsLabelChecks.getAndIncrement() == 0);

        Cluster saved = clusterService.createCluster(orgId, new ObjectId(),
            "us-east-1", "Production", "basic", "user@example.com", "1.0.0");

        assertThat(saved.getName()).isEqualTo("Production");
        assertThat(saved.getDnsLabel()).startsWith("production-");
        assertThat(saved.getDnsLabel()).endsWith(saved.getId().toHexString() + "-1");
        assertThat(DnsLabels.isValidLabel(saved.getDnsLabel())).isTrue();
    }

    @Test
    void mapsDnsLabelIndexDuplicateFromConcurrentInsert() {
        when(clusterRepository.save(any(Cluster.class)))
            .thenThrow(new DuplicateKeyException(
                "E11000 duplicate key error collection: cloud.clusters index: dns_label_active_unique"));

        assertThatThrownBy(() -> clusterService.createCluster(orgId, new ObjectId(),
            "ap-southeast-1", "Production", "basic", "user@example.com", "1.0.0"))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(AppErrorCode.CLUSTER_DNS_LABEL_CONFLICT));
    }

    @Test
    void mapsNameIndexDuplicateFromConcurrentInsert() {
        when(clusterRepository.save(any(Cluster.class)))
            .thenThrow(new DuplicateKeyException(
                "E11000 duplicate key error collection: cloud.clusters index: org_region_name_active_unique"));

        assertThatThrownBy(() -> clusterService.createCluster(orgId, new ObjectId(),
            "ap-southeast-1", "Production", "basic", "user@example.com", "1.0.0"))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getErrorCode()).isEqualTo(AppErrorCode.CLUSTER_ALREADY_EXISTS));
    }
}
