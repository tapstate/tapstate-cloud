package io.tapstate.cloud.identity;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EmailVerificationChallengeRepository extends MongoRepository<EmailVerificationChallenge, ObjectId> {

    Optional<EmailVerificationChallenge> findByKey(String key);
}
