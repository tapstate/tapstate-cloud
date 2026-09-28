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

    /** Find all active (non-revoked) JWT records for a user — REGARDLESS of JWT expiry.
     *  Cluster local sessions outlive the 15min JWT window (30min sliding idle → max ~24h),
     *  so Cloud logout must fire revocation even for expired JWTs whose Cluster sessions may still be active. */
    List<JtiRecord> findByUserEmailAndRevokedAtIsNull(String userEmail);

    Optional<JtiRecord> findByJti(String jti);
}
