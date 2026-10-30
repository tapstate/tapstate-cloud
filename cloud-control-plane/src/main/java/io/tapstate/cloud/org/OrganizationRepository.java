package io.tapstate.cloud.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

@Repository
public interface OrganizationRepository extends MongoRepository<Organization, ObjectId> {

    Optional<Organization> findBySlug(String slug);

    Optional<Organization> findByCreationKey(String creationKey);

    List<Organization> findByOwnerEmail(String ownerEmail);

    List<Organization> findByStatus(String status);
}
