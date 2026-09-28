package io.tapstate.cloud.region;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegionRepository extends MongoRepository<Region, String> {

    List<Region> findByOpenTrueOrderBySortOrderAsc();

    boolean existsByCode(String code);
}
