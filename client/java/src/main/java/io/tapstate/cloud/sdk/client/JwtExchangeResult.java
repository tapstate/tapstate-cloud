/*
 * Tapstate Cloud — Global Control Plane SDK
 * Project-owned facade classes — NOT auto-generated.
 * Protected by .openapi-generator-ignore.
 */

package io.tapstate.cloud.sdk.client;

import java.time.Instant;

/**
 * Result of a successful exchange_code → resolve JWT redemption (ADR-0125).
 * <p>
 * Back-channel only — the JWT never traverses the browser. The Cluster Server
 * runtime uses this to establish its HttpOnly Cookie session and to carry the
 * JWT on subsequent workload API calls.
 *
 * @param jwt        Resolve JWT string (RS256, 15min TTL) — the workload bearer token
 * @param expiresAt  JWT expiration timestamp
 * @param jti        Unique JWT ID — for server-side audit
 * @param userEmail  User who initiated the Cloud Console → Cluster Console handoff
 * @param orgId      Organization UUID the user belongs to
 * @param clusterId  Cluster UUID this JWT is scoped to
 * @param organizationName Organization display name for the established ClusterContext
 * @param clusterName Cluster display name for the established ClusterContext
 * @param region AWS region for the established ClusterContext
 */
public record JwtExchangeResult(
    String jwt,
    Instant expiresAt,
    String jti,
    String userEmail,
    String orgId,
    String clusterId,
    String organizationName,
    String clusterName,
    String region
) {}
