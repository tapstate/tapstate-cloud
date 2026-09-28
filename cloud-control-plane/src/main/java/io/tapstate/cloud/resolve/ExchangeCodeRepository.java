package io.tapstate.cloud.resolve;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for one-time exchange codes (ADR-0125 D2).
 * <p>
 * The atomic conditional redemption lives in {@link ExchangeService} using
 * Spring Data Mongo's {@code findAndModify} — this repo only provides the
 * initial lookup needed for the conditional write guard.
 */
@Repository
public interface ExchangeCodeRepository extends MongoRepository<ExchangeCode, String> {

    /**
     * Find an unconsumed code by hash. The caller must additionally check
     * {@code expires_at > now} and perform the atomic consume operation.
     */
    Optional<ExchangeCode> findByCodeHashAndConsumedAtIsNull(String codeHash);

    /** Count expired + consumed rows for scheduled cleanup job (Week 3+). */
    long countByConsumedAtIsNotNullAndExpiresAtBefore(Instant threshold);

    /** Delete consumed codes older than the threshold — scheduled cleanup. */
    void deleteByConsumedAtIsNotNullAndExpiresAtBefore(Instant threshold);
}
