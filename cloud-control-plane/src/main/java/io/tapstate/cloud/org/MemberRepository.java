package io.tapstate.cloud.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

@Repository
public interface MemberRepository extends MongoRepository<Member, ObjectId> {

    List<Member> findByOrgId(ObjectId orgId);

    List<Member> findByUserEmail(String userEmail);

    Optional<Member> findByOrgIdAndUserEmail(ObjectId orgId, String userEmail);

    boolean existsByOrgIdAndUserEmail(ObjectId orgId, String userEmail);
}
