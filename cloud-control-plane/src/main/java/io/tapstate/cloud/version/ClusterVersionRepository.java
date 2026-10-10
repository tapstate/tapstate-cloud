package io.tapstate.cloud.version;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClusterVersionRepository extends MongoRepository<ClusterVersion, ObjectId> {

    /** All versions in the given state, newest first (descending {@code versionNumber}). */
    List<ClusterVersion> findByStateOrderByVersionNumberDesc(ClusterVersionState state);

    /** Newest version in the given state, or empty when none exists. */
    Optional<ClusterVersion> findFirstByStateOrderByVersionNumberDesc(ClusterVersionState state);

    /** Look up one version by its version string. */
    Optional<ClusterVersion> findByVersion(String version);

    /** Whether a version with the given string already exists. */
    boolean existsByVersion(String version);
}
