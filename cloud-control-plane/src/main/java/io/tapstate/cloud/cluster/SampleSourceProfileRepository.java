package io.tapstate.cloud.cluster;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SampleSourceProfileRepository extends MongoRepository<SampleSourceProfile, String> {
    List<SampleSourceProfile> findAllByOrderBySortOrderAsc();
}
