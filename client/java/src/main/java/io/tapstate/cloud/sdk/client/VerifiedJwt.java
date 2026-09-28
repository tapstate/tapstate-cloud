/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned facade classes — NOT auto-generated.
 * Protected by .openapi-generator-ignore.
 */

package io.tapstate.cloud.sdk.client;

import java.time.Instant;
import java.util.List;

/**
 * Result of successful resolve JWT offline verification (ADR-0122 D4).
 * <p>
 * All fields are extracted from verified JWT claims — the caller can trust
 * them without additional backend calls (that's the whole point of offline JWKS).
 *
 * @param userId    JWT custom claim — stable user UUID (Issue #5 item 1)
 * @param userEmail  JWT {@code sub} — the user who initiated the Cloud → Cluster handoff
 * @param orgId      JWT custom claim — Organization UUID (ADR-0122 D4)
 * @param clusterId  JWT custom claim — Cluster UUID this JWT is scoped to
 * @param jti        JWT {@code jti} — unique token ID (for server-side audit + revocation)
 * @param expiresAt  JWT {@code exp} — expiration timestamp
 * @param issuer     JWT {@code iss} — Global Control Plane issuer URL
 * @param audience   JWT {@code aud} — expected Cluster audience ({@code <clusterId>.api.tapstate.io})
 * @param scope      JWT custom claim — permission scope list (default: workload:read/write)
 */
public record VerifiedJwt(
    String userId,
    String userEmail,
    String orgId,
    String clusterId,
    String jti,
    Instant expiresAt,
    String issuer,
    String audience,
    List<String> scope
) {}
