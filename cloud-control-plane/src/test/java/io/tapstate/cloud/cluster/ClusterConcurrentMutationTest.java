package io.tapstate.cloud.cluster;

import com.mongodb.client.result.UpdateResult;
import io.tapstate.cloud.cluster.fsm.ClusterStateMachineService;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.region.RegionService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterConcurrentMutationTest {
    @Mock ClusterRepository clusters;
    @Mock ClusterStatusReportHistoryRepository history;
    @Mock MemberRepository members;
    @Mock RegionService regions;
    @Mock ClusterStateMachineService stateMachine;
    @Mock MongoTemplate mongo;
    @InjectMocks ClusterService service;

    private final Cluster stale = new Cluster();
    private final Document durable = new Document();
    private final Instant token = Instant.parse("2026-10-15T00:00:00Z");

    @BeforeEach
    void setUp() {
        stale.setId(new ObjectId());
        stale.setState(ClusterState.READY);
        stale.setEc2PublicIp("198.51.100.1");
        stale.setEgressAddressStatus(ClusterEgressAddressStatus.DYNAMIC);
        when(clusters.findById(stale.getId())).thenAnswer(invocation -> {
            // The read returns its old snapshot while an address operation commits.
            durable.put("ec2PublicIp", "203.0.113.42");
            durable.put("egressAddressAllocationId", "eipalloc-owned");
            durable.put("egressAddressStatus", ClusterEgressAddressStatus.ATTACHING);
            durable.put("egressAddressOperationStartedAt", token);
            return Optional.of(stale);
        });
        when(clusters.save(any(Cluster.class))).thenAnswer(invocation -> {
            Cluster saved = invocation.getArgument(0);
            durable.put("ec2PublicIp", saved.getEc2PublicIp());
            durable.put("egressAddressAllocationId", saved.getEgressAddressAllocationId());
            durable.put("egressAddressStatus", saved.getEgressAddressStatus());
            durable.put("egressAddressOperationStartedAt", saved.getEgressAddressOperationStartedAt());
            return saved;
        });
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Cluster.class)))
            .thenAnswer(invocation -> {
                Update update = invocation.getArgument(1);
                durable.putAll(update.getUpdateObject().get("$set", Document.class));
                return UpdateResult.acknowledged(1, 1L, null);
            });
    }

    @Test
    void heartbeatDoesNotOverwriteAnAddressOperationCommittedAfterItsRead() {
        service.recordStatusReport(stale.getId().toHexString(),
            StatusReportRequest.builder().nonce("fresh-nonce").build());
        assertThat(durable).containsEntry("ec2PublicIp", "203.0.113.42")
            .containsEntry("egressAddressAllocationId", "eipalloc-owned")
            .containsEntry("egressAddressStatus", ClusterEgressAddressStatus.ATTACHING)
            .containsEntry("egressAddressOperationStartedAt", token);
    }
}
