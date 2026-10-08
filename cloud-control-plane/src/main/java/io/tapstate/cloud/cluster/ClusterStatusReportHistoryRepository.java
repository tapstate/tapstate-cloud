package io.tapstate.cloud.cluster;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for {@link ClusterStatusReportHistory} — MongoDB time-series collection.
 * <p>
 * Query patterns:
 * <ul>
 *   <li>Latest N reports for a specific cluster (time-ordered descending)</li>
 *   <li>All reports for a cluster within a time range</li>
 * </ul>
 */
public interface ClusterStatusReportHistoryRepository
    extends MongoRepository<ClusterStatusReportHistory, ObjectId> {

    /** Latest N status reports for a cluster, most recent first. */
    List<ClusterStatusReportHistory> findByClusterIdOrderByTimestampDesc(String clusterId, Pageable pageable);

    /** All status reports for a cluster within [since, until] window, oldest first. */
    List<ClusterStatusReportHistory> findByClusterIdAndTimestampBetweenOrderByTimestampAsc(
        String clusterId, Instant since, Instant until);
}
