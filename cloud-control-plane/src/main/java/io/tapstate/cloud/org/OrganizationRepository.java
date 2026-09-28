package io.tapstate.cloud.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends MongoRepository<Organization, String> {

    Optional<Organization> findBySlug(String slug);

    List<Organization> findByOwnerEmail(String ownerEmail);

    List<Organization> findByStatus(String status);
}
