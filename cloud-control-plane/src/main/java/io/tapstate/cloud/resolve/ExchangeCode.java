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

/**
 * One-time exchange code — MongoDB document stores ONLY the SHA-256 hash,
 * never the raw code. TTL = 60 seconds (ADR-0125 D2).
 * <p>
 * ADR-0125 D4: consumption is an atomic conditional write — {@code consumed_at}
 * is set in the same operation that verifies the code is unconsumed, unexpired,
 * and the caller is the owning Cluster service identity.
 */
@Document("one_time_exchange_codes")
@CompoundIndex(name = "code_hash_unconsumed", def = "{'codeHash': 1, 'consumedAt': 1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeCode {

    @Id
    private String id;

    /** SHA-256 hex digest of the raw exchange code — the raw value NEVER enters the DB. */
    @Indexed(unique = true)
    private String codeHash;

    /** User who requested the Cluster Console handoff (from valid Cloud session). */
    private String userEmail;

    /** Stable user ID (UUID) — added to resolve JWT as claim "user_id" (Issue #5 item 1). */
    private String userId;

    /** Organization the user belongs to. */
    private String orgId;

    /** Target Cluster — resolution bound to this Cluster's existence and ready state. */
    private String clusterId;

    /** Cluster origin the browser will be redirected to (e.g. https://cluster-a.tapstate.io). */
    private String targetOrigin;

    /** Code issued at. */
    private Instant createdAt;

    /** Code expiration — 60 seconds after creation. */
    private Instant expiresAt;

    /**
     * Consumed timestamp — {@code null} until the code is atomically redeemed.
     * Once set, the same code can never be redeemed again (retry-safe idempotency).
     */
    private Instant consumedAt;
}
