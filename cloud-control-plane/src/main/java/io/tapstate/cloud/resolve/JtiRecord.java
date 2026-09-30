package io.tapstate.cloud.resolve;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Tracks an issued resolve JWT for Cloud-side logout revocation (ADR-0125 D4).
 * <p>
 * When Cloud signs a resolve JWT during {@link ExchangeService#redeemExchangeCode},
 * it persists one JtiRecord so that a later Cloud-side {@code POST /auth/logout}
 * can find ALL active resolve JWTs for that user and fire signed revocation
 * callbacks to each target Cluster.
 * <p>
 * Lifecycle: created at JWT issue → {@code revokedAt = null} while valid →
 * set to {@code now} when Cluster confirms revocation or JWT expires.
 * Records are NOT auto-cleaned (retention by TTL index or manual sweep).
 */
@Document("jti_records")
@CompoundIndex(name = "user_active", def = "{'userEmail': 1, 'revokedAt': 1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JtiRecord {

    @Id
    private ObjectId id;

    /** JWT jti claim — globally unique per token. */
    @Indexed(unique = true)
    private String jti;

    /** User email who initiated the Cloud → Cluster handoff. */
    private String userEmail;

    /** Stable user ID (JWT claim "user_id"). */
    private ObjectId userId;

    /** Organization ID. */
    private ObjectId orgId;

    /** Cluster ID the JWT is scoped to. */
    private ObjectId clusterId;

    /** Cluster base URI for Cloud→Cluster revocation callback. */
    private String clusterBaseUri;

    /** JWT issued-at timestamp (Cloud side). */
    private Instant issuedAt;

    /** JWT exp claim — expiry timestamp. NOT the TTL index (see retentionUntil below). */
    private Instant expiresAt;

    /** Revocation timestamp — null while active, set on logout or expiry. */
    private Instant revokedAt;

    /**
     * Cloud-side retention window — TTL index auto-deletes records this many days after expiry.
     * Must be LONGER than the Cluster's local session lifetime (30min sliding idle → max ~24h after JWT issue).
     * We use 7 days to be safe; MongoDB's TTL index fires at a minute-level granularity.
     */
    @Indexed(expireAfterSeconds = 7 * 24 * 3600)
    private Instant retentionUntil;
}
