package io.tapstate.cloud.mdb;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data Mongo repository for {@link AtlasInstanceConfig}. */
public interface AtlasInstanceConfigRepository extends MongoRepository<AtlasInstanceConfig, String> {

    /** Find ACTIVE instances matching the given region, ordered by currentCustomers ascending (least-loaded first). */
    List<AtlasInstanceConfig> findByStatusAndRegionOrderByCurrentCustomersAsc(
        AtlasInstanceConfig.ConfigStatus status, String region);

    /** All ACTIVE instances — no region preference (fallback path). */
    List<AtlasInstanceConfig> findByStatusOrderByCurrentCustomersAsc(
        AtlasInstanceConfig.ConfigStatus status);

    Optional<AtlasInstanceConfig> findByIdAndStatus(String id, AtlasInstanceConfig.ConfigStatus status);
}
