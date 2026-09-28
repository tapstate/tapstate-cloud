package io.tapstate.cloud.org;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends MongoRepository<Member, String> {

    List<Member> findByOrgId(String orgId);

    List<Member> findByUserEmail(String userEmail);

    Optional<Member> findByOrgIdAndUserEmail(String orgId, String userEmail);

    boolean existsByOrgIdAndUserEmail(String orgId, String userEmail);
}
