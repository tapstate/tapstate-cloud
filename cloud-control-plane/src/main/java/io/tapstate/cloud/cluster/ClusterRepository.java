package io.tapstate.cloud.cluster;

import org.springframework.data.domain.Pageable;
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

    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'cloudFormationStackStatus': ?1 } }")
    void updateStackStatus(ObjectId id, String status);

    List<Cluster> findByOrgId(ObjectId orgId);

    List<Cluster> findByOrgIdAndRegion(ObjectId orgId, String region);

    List<Cluster> findByOrgIdAndState(ObjectId orgId, ClusterState state);

    /** Every Cluster in one lifecycle state — used by the provisioning reconciler. */
    List<Cluster> findByState(ClusterState state);

    /** A bounded lifecycle-state page ordered by the caller's recovery policy. */
    List<Cluster> findByState(ClusterState state, Pageable pageable);

    Optional<Cluster> findByIdAndOrgId(ObjectId id, ObjectId orgId);

    List<Cluster> findBySubscriptionId(ObjectId subscriptionId);

    Optional<Cluster> findByIdAndSubscriptionId(ObjectId id, ObjectId subscriptionId);

    boolean existsByOrgIdAndRegionAndNameAndStateNot(ObjectId orgId, String region, String name,
                                                      ClusterState state);

    /**
     * Global active-Cluster check for the DNS label derived from the display
     * name: one environment shares a hosted zone, so the subdomain must be
     * unique across every org and region.
     */
    boolean existsByDnsLabelAndStateNot(String dnsLabel, ClusterState state);

    /** A hostname cannot be removed while another live Cluster owns its DNS record. */
    boolean existsByPublicDnsAndStateNotAndIdNot(String publicDns, ClusterState state, ObjectId id);

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
