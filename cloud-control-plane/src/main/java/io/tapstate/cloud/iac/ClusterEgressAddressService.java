package io.tapstate.cloud.iac;

import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterEgressAddressStatus;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.iac.model.NodeInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterEgressAddressService {

    private final ClusterRepository clusterRepository;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher events;
    private final DnsInfrastructure dns;
    private final Map<String, ComputeInfrastructure> computeBeans;

    @Value("${tapstate.cloud.iac.mode:real}")
    private String mode = "real";

    public Cluster requestStabilization(Cluster cluster) {
        Cluster latest = clusterRepository.findById(cluster.getId()).orElse(cluster);
        if (!isLive(latest)) {
            throw new BusinessException(AppErrorCode.CLUSTER_INVALID_STATE, 409, latest.getState());
        }
        ClusterEgressAddressStatus current = effectiveStatus(latest);
        if (current == ClusterEgressAddressStatus.STABLE
                || current == ClusterEgressAddressStatus.STABILIZING
                || current == ClusterEgressAddressStatus.ATTACHING) {
            return latest;
        }

        Instant startedAt = Instant.now();
        Criteria statusCanStart = new Criteria().orOperator(
            Criteria.where("egressAddressStatus").is(null),
            Criteria.where("egressAddressStatus").in(
                ClusterEgressAddressStatus.DYNAMIC, ClusterEgressAddressStatus.FAILED));
        Query query = Query.query(new Criteria().andOperator(
            Criteria.where("_id").is(latest.getId()),
            Criteria.where("state").in(ClusterState.READY, ClusterState.DEGRADED),
            statusCanStart));
        Update update = new Update()
            .set("egressAddressStatus", ClusterEgressAddressStatus.STABILIZING)
            .set("egressAddressOperationStartedAt", startedAt);
        Cluster saved = mongoTemplate.findAndModify(query, update,
            FindAndModifyOptions.options().returnNew(true), Cluster.class);
        if (saved == null) {
            Cluster currentCluster = clusterRepository.findById(latest.getId()).orElse(latest);
            ClusterEgressAddressStatus currentStatus = effectiveStatus(currentCluster);
            if (currentStatus == ClusterEgressAddressStatus.STABLE
                    || currentStatus == ClusterEgressAddressStatus.STABILIZING
                    || currentStatus == ClusterEgressAddressStatus.ATTACHING) {
                return currentCluster;
            }
            throw new BusinessException(AppErrorCode.CLUSTER_INVALID_STATE, 409, currentCluster.getState());
        }
        events.publishEvent(new StabilizationRequest(saved.getId(), startedAt, false));
        return saved;
    }

    @Async
    @EventListener
    public void onStabilizationRequested(StabilizationRequest request) {
        Optional<Instant> lease = claim(request);
        if (lease.isEmpty()) return;
        Instant operationToken = lease.get();

        Cluster cluster = clusterRepository.findById(request.clusterId()).orElse(null);
        if (cluster == null || !isLive(cluster)
                || !operationToken.equals(cluster.getEgressAddressOperationStartedAt())) return;

        try {
            NodeInstance node = awsCompute().stabilizeEgressAddress(cluster.getRegion(),
                cluster.getId().toHexString(), cluster.getEgressAddressAllocationId());
            if (node.publicIp() == null || node.publicIp().isBlank()
                    || node.egressAddressAllocationId() == null
                    || node.egressAddressAllocationId().isBlank()) {
                throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                    "Stable egress address allocation was not returned for the Cluster");
            }

            // Association has already replaced the dynamic address, even if DNS subsequently fails.
            if (!recordAssociation(cluster.getId(), operationToken, node)) return;

            String domain = cluster.getDnsRecord() != null ? cluster.getDnsRecord() : cluster.getPublicDns();
            if (domain == null || domain.isBlank()) {
                throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                    "Cluster DNS name is missing while stabilizing its public address");
            }
            dns.upsertARecord(domain, node.publicIp());

            if (!complete(cluster.getId(), operationToken, node)) {
                log.info("[EgressAddress] Cluster changed while address was stabilizing cluster={}",
                    cluster.getId().toHexString());
                return;
            }
            log.info("[EgressAddress] Cluster address stabilized cluster={} ip={}",
                cluster.getId().toHexString(), node.publicIp());
        } catch (Exception failure) {
            fail(cluster.getId(), operationToken);
            log.error("[EgressAddress] Cluster address stabilization failed cluster={} type={}",
                request.clusterId().toHexString(), failure.getClass().getSimpleName());
        } finally {
            clusterRepository.findById(request.clusterId())
                .filter(current -> current.getState() == ClusterState.TERMINATED)
                .ifPresent(this::reclaimTerminatedResources);
        }
    }

    /**
     * TERMINATED records are durable cleanup intents. Keep reconciling their tagged resources:
     * an in-flight worker may finish an AWS or DNS request after an earlier cleanup succeeds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcileTerminatedClusters() {
        if (!"real".equalsIgnoreCase(mode)) return;
        for (Cluster cluster : clusterRepository.findByState(ClusterState.TERMINATED)) {
            reclaimTerminatedResources(cluster);
        }
    }

    private void reclaimTerminatedResources(Cluster cluster) {
        try {
            awsCompute().terminate(cluster.getRegion(), cluster.getId().toHexString());
            String domain = cluster.getDnsRecord() != null ? cluster.getDnsRecord() : cluster.getPublicDns();
            if (domain != null && !domain.isBlank()) dns.deleteRecord(domain);
        } catch (Exception failure) {
            log.warn("[EgressAddress] terminated Cluster cleanup will retry cluster={} type={}",
                cluster.getId().toHexString(), failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void resumeStaleStabilizations() {
        Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);
        for (ClusterEgressAddressStatus status : List.of(
                ClusterEgressAddressStatus.STABILIZING, ClusterEgressAddressStatus.ATTACHING)) {
            for (Cluster cluster : clusterRepository.findByEgressAddressStatusAndEgressAddressOperationStartedAtBefore(
                    status, threshold)) {
                events.publishEvent(new StabilizationRequest(cluster.getId(),
                    cluster.getEgressAddressOperationStartedAt(), true));
            }
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void reconcileReplacedNodes() {
        List<Cluster> stableClusters = clusterRepository.findByEgressAddressStatusAndStateIn(
            ClusterEgressAddressStatus.STABLE, List.of(ClusterState.READY, ClusterState.DEGRADED));
        for (Cluster cluster : stableClusters) {
            try {
                NodeInstance currentNode = awsCompute().currentNode(cluster.getRegion(), cluster.getId().toHexString());
                if (Objects.equals(currentNode.instanceId(), cluster.getEc2InstanceId())
                        && Objects.equals(currentNode.publicIp(), cluster.getEc2PublicIp())
                        && Objects.equals(currentNode.egressAddressAllocationId(),
                            cluster.getEgressAddressAllocationId())) continue;

                Instant startedAt = Instant.now();
                Criteria criteria = new Criteria().andOperator(
                    Criteria.where("_id").is(cluster.getId()),
                    Criteria.where("state").in(ClusterState.READY, ClusterState.DEGRADED),
                    Criteria.where("egressAddressStatus").is(ClusterEgressAddressStatus.STABLE),
                    Criteria.where("ec2InstanceId").is(cluster.getEc2InstanceId()));
                Cluster claimed = mongoTemplate.findAndModify(Query.query(criteria),
                    new Update()
                        .set("egressAddressStatus", ClusterEgressAddressStatus.ATTACHING)
                        .set("egressAddressOperationStartedAt", startedAt),
                    FindAndModifyOptions.options().returnNew(true), Cluster.class);
                if (claimed != null) {
                    events.publishEvent(new StabilizationRequest(claimed.getId(), startedAt, true));
                }
            } catch (Exception failure) {
                log.warn("[EgressAddress] could not inspect Cluster node for replacement cluster={} type={}",
                    cluster.getId().toHexString(), failure.getClass().getSimpleName());
            }
        }
    }

    private Optional<Instant> claim(StabilizationRequest request) {
        Instant token = Instant.now();
        Criteria criteria = Criteria.where("_id").is(request.clusterId())
            .and("egressAddressOperationStartedAt").is(request.startedAt())
            .and("state").in(ClusterState.READY, ClusterState.DEGRADED);
        Update update = new Update().set("egressAddressOperationStartedAt", token);
        if (request.recovery()) {
            criteria.and("egressAddressStatus").in(
                ClusterEgressAddressStatus.ATTACHING, ClusterEgressAddressStatus.STABILIZING);
            update.set("egressAddressStatus", ClusterEgressAddressStatus.ATTACHING);
        } else {
            criteria.and("egressAddressStatus").is(ClusterEgressAddressStatus.STABILIZING);
            update.set("egressAddressStatus", ClusterEgressAddressStatus.ATTACHING);
        }
        Cluster claimed = mongoTemplate.findAndModify(Query.query(criteria), update,
            FindAndModifyOptions.options().returnNew(true), Cluster.class);
        return claimed == null ? Optional.empty() : Optional.of(claimed.getEgressAddressOperationStartedAt());
    }

    private boolean complete(ObjectId clusterId, Instant token, NodeInstance node) {
        Query query = Query.query(liveOperation(clusterId, token));
        Update update = new Update()
            .set("egressAddressStatus", ClusterEgressAddressStatus.STABLE)
            .unset("egressAddressOperationStartedAt");
        return mongoTemplate.updateFirst(query, update, Cluster.class).getMatchedCount() == 1;
    }

    private boolean recordAssociation(ObjectId clusterId, Instant token, NodeInstance node) {
        Update update = new Update()
            .set("ec2InstanceId", node.instanceId())
            .set("ec2PublicIp", node.publicIp())
            .set("egressAddressAllocationId", node.egressAddressAllocationId());
        return mongoTemplate.updateFirst(Query.query(liveOperation(clusterId, token)), update, Cluster.class)
            .getMatchedCount() == 1;
    }

    private void fail(ObjectId clusterId, Instant token) {
        Query query = Query.query(liveOperation(clusterId, token));
        Update update = new Update()
            .set("egressAddressStatus", ClusterEgressAddressStatus.FAILED)
            .unset("egressAddressOperationStartedAt");
        mongoTemplate.updateFirst(query, update, Cluster.class);
    }

    private static Criteria liveOperation(ObjectId clusterId, Instant token) {
        return new Criteria().andOperator(
            Criteria.where("_id").is(clusterId),
            Criteria.where("egressAddressOperationStartedAt").is(token),
            Criteria.where("egressAddressStatus").is(ClusterEgressAddressStatus.ATTACHING),
            Criteria.where("state").in(ClusterState.READY, ClusterState.DEGRADED));
    }

    private ComputeInfrastructure awsCompute() {
        return computeBeans.values().stream()
            .filter(candidate -> candidate.provider() == CloudProvider.AWS)
            .findFirst()
            .orElseThrow(() -> new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                "AWS compute infrastructure is unavailable"));
    }

    private static ClusterEgressAddressStatus effectiveStatus(Cluster cluster) {
        return cluster.getEgressAddressStatus() == null
            ? ClusterEgressAddressStatus.DYNAMIC : cluster.getEgressAddressStatus();
    }

    private static boolean isLive(Cluster cluster) {
        return cluster.getState() == ClusterState.READY || cluster.getState() == ClusterState.DEGRADED;
    }

    public record StabilizationRequest(ObjectId clusterId, Instant startedAt, boolean recovery) {
    }
}
