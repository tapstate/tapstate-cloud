package io.tapstate.cloud.cluster;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

@Repository
public interface ClusterRepository extends MongoRepository<Cluster, ObjectId> {

    List<Cluster> findByState(ClusterState state);

    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'cloudFormationStackStatus': ?1 } }")
    void updateStackStatus(ObjectId id, String status);

    List<Cluster> findByOrgId(ObjectId orgId);

    List<Cluster> findByOrgIdAndRegion(ObjectId orgId, String region);

    List<Cluster> findByOrgIdAndState(ObjectId orgId, ClusterState state);

    Optional<Cluster> findByIdAndOrgId(ObjectId id, ObjectId orgId);

    List<Cluster> findBySubscriptionId(ObjectId subscriptionId);

    Optional<Cluster> findByIdAndSubscriptionId(ObjectId id, ObjectId subscriptionId);

    boolean existsByOrgIdAndRegionAndNameAndStateNot(ObjectId orgId, String region, String name,
                                                      ClusterState state);

    /**
     * Find READY clusters whose last heartbeat was more than 5 minutes ago.
     * Used by scheduled task to auto-transition to DEGRADED.
     */
    @Query("{ 'state': ?0, 'lastHeartbeatAt': { $lt: ?1 } }")
    List<Cluster> findReadyClustersWithExpiredHeartbeat(ClusterState state, Instant threshold);

    /**
     * Find DEGRADED clusters whose heartbeat has recovered.
     */
    @Query("{ 'state': ?0, 'lastHeartbeatAt': { $gte: ?1 } }")
    List<Cluster> findDegradedClustersWithRecentHeartbeat(ClusterState state, Instant threshold);

    /**
     * Find Cluster by SDK static token — used by C2 status-report authentication
     * (runtime authenticates itself with its per-Cluster Bearer token).
     */
    Optional<Cluster> findBySdkStaticToken(String sdkStaticToken);

    /**
     * Find live Clusters in the given states that have a reachable node
     * (public IP and encrypted SSH key recorded). Used by the TLS certificate
     * distribution sweep over READY and DEGRADED Clusters.
     */
    List<Cluster> findByStateInAndEc2PublicIpIsNotNullAndSshPrivateKeyEncIsNotNull(
        java.util.Collection<ClusterState> states);

    List<Cluster> findByEgressAddressStatusAndEgressAddressOperationStartedAtBefore(
        ClusterEgressAddressStatus status, Instant threshold);

    List<Cluster> findByEgressAddressStatusAndStateIn(
        ClusterEgressAddressStatus status, java.util.Collection<ClusterState> states);
}
