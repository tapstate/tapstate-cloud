package io.tapstate.cloud.resolve;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link JtiRecord} — tracks issued resolve JWTs for revocation.
 */
@Repository
public interface JtiRecordRepository extends MongoRepository<JtiRecord, String> {

    /** Find all active (non-revoked, non-expired) JWT records for a user. */
    List<JtiRecord> findByUserEmailAndRevokedAtIsNullAndExpiresAtAfter(String userEmail, Instant now);

    Optional<JtiRecord> findByJti(String jti);
}
