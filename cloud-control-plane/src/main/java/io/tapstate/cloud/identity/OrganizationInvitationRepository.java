package io.tapstate.cloud.identity;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationInvitationRepository extends MongoRepository<OrganizationInvitation, ObjectId> {

    Optional<OrganizationInvitation> findByTokenHash(String tokenHash);

    List<OrganizationInvitation> findByOrgIdOrderByCreatedAtDesc(ObjectId orgId);
}
