# TapState Cloud — Control Plane SDK

Maven artifact: `io.tapstate:cloud-control-plane-sdk`

A small, thread-safe Java library for Cluster runtimes (Pipeline Runtime, Cluster
Console) to talk to the Cloud Global Control Plane. Two high-level facade methods
over the auto-generated OpenAPI client — the facade lives under `src/main/java/.../client/`
and is **protected from regeneration** by `.openapi-generator-ignore`.

Requires **Java 21+** (Maven release `21`, Jackson Java 21 time module).

---

## Maven

```xml
<dependency>
  <groupId>io.tapstate</groupId>
  <artifactId>cloud-control-plane-sdk</artifactId>
  <version>${project.version}</version>
</dependency>
```

Dependencies are transitive: Apache HttpClient 5, Jackson, JJWT 0.12, SLF4J.
No Spring Boot.

---

## Configuration

The SDK is built with environment variables as defaults — set them once per
Cluster deployment, then callers never need to repeat them.

| Env var | Required | Where used |
|---|---|---|
| `CLUSTER_ID` | Only `exchangeForJwt()` | Cluster UUID, sent as `cluster_id` in the exchange request |
| `CLUSTER_IDENTITY_SECRET` | Only `exchangeForJwt()` | Per-Cluster service identity secret, sent as `X-Cluster-Identity-Secret` header |

Both can be overridden via `Builder.clusterId(...)` / `Builder.clusterIdentitySecret(...)`
for tests or special deployments.

Common builder options:

```java
CloudControlPlaneSdk sdk = CloudControlPlaneSdk.builder()
    .baseUrl("https://cloud.tapstate.io")          // required
    .issuer("https://cloud.tapstate.io")           // default — GC's signer; change if self-hosted
    .clusterIdentitySecret(System.getenv("CLUSTER_IDENTITY_SECRET"))
    .clusterId(System.getenv("CLUSTER_ID"))        // default — reads env at builder creation
    .jwksRefreshInterval(Duration.ofMinutes(10))   // default — 10 min (ADR-0125 D6)
    .build();
```

Thread-safe: one `CloudControlPlaneSdk` instance may be shared across threads.
Each instance owns its own `ApiClient` and `JwksKeyCache` — never mutate the
global `Configuration.getDefaultApiClient()` singleton.

---

## Function 1 — exchange_code → resolve JWT (ADR-0125)

The browser carries the raw `exchange_code` from the Cloud Console URL query
(`?code=...`) into the Cluster Console. The Cluster Server backend calls
**this method** — never exposes the JWT to the browser (back-channel only).

```java
try {
    JwtExchangeResult result = sdk.exchangeForJwt(exchangeCode);
    // result.jwt()        → carry as bearer token on workload requests
    // result.expiresAt()  → Instant, GC default is 15 min
    // result.jti()        → unique token id
    // result.userEmail()  → who logged in at Cloud Console
    // result.orgId()      → Organization UUID
    // result.clusterId()  → Cluster UUID (should match sdk.getClusterId())
} catch (CloudControlPlaneException cpe) {
    // cpe.getErrorCode()  → e.g. "exchange.cluster-identity-mismatch"
    // cpe.getHttpStatus()  → 400 / 401 / 500 / 0 (network)
    // cpe.getServerMessage() → GC's English error text
    // Handle by showing the user a retry / contact admin message
}
```

Throws typed `CloudControlPlaneException` — NOT raw `ApiException`.
Common error codes:

| code | http | meaning |
|---|---|---|
| `exchange.cluster-identity-mismatch` | 401 | `CLUSTER_IDENTITY_SECRET` doesn't match GC's record for this Cluster |
| `exchange.code-expired` | 401 | exchange_code's 60-second TTL ran out |
| `exchange.code-consumed` | 401 | exchange_code was already redeemed by another request (race) |
| `sdk.network-error` | 0 | GC unreachable — log and return 503 to client |

---

## Function 2 — offline JWKS JWT verification (ADR-0122 D5 / ADR-0125 D6)

After the initial exchange, every subsequent workload request carries the resolve
JWT in `Authorization: Bearer`. The Cluster Server must **never call GC online**
for per-request auth — JWKS is cached locally.

```java
try {
    VerifiedJwt v = sdk.verifyJwt(bearerToken);
    // v.userEmail()   → who
    // v.orgId()        → cross-check: does this JWT belong to OUR org?
    // v.clusterId()    → cross-check: does this JWT belong to OUR cluster?
    // v.scope()        → List<String>, e.g. ["workload:read", "workload:write"]
    // v.issuer()       → should equal the configured issuer
    // v.expiresAt()    → Instant
    // v.jti()          → for replay detection (if needed)
} catch (JwtVerificationException ex) {
    switch (ex.getKind()) {
        case "jwk-not-found"     -> log.warn("GC key rotation, will refresh on next request");
        case "signature-mismatch" -> reject as tampered token
        case "expired"           -> reject — send client back to Cluster Console for re-exchange
        case "missing-kid", "parse-error" -> reject — malformed token
        case "bad-issuer"        -> reject — token signed by wrong GC
        default                  -> reject — unknown failure
    }
} catch (CloudControlPlaneException cpe) {
    // JWKS cache is empty (first fetch failed, no stale keys to fall back to)
    // Log + return 503 — Cluster can't auth until it talks to GC
}
```

### What `verifyJwt` does NOT do

The SDK only verifies **signature + exp + iss** and extracts **all** claims into
`VerifiedJwt`. It does NOT compare `org_id`, `cluster_id`, or `aud` against
local values — the Cluster already knows which org/cluster it serves and
should do that cross-check itself after calling `verifyJwt()`, e.g.:

```java
VerifiedJwt v = sdk.verifyJwt(bearerToken);
if (!sdk.getClusterId().equals(v.clusterId())) {
    throw new JwtVerificationException.BadClaim("cluster_id", v.clusterId(), sdk.getClusterId());
}
// org_id cross-check is analogous
```

`BadClaim` / `BadAudience` subtypes exist on `JwtVerificationException` **for
the caller** — the SDK never throws them.

### JWKS cache behavior

- **Refresh interval** — 10 min default, configurable via builder
- **Stale-on-failure** — if refresh fails, old keys are kept; only the first
  fetch (empty cache) propagates failure
- **Auto force-refresh** — if a JWT's `kid` is not in cache, the SDK tries
  `forceRefresh()` **once** before giving up — handles most GC key-rotation cases
- **Single-flight** — concurrent callers during a refresh wait on the in-flight
  one; no thundering herd

### Exception hierarchy

All `JwtVerificationException` subtypes have a `getKind()` string for branching
(see example above).

| `getKind()` | Meaning |
|---|---|
| `missing-kid` | JWT header has no `kid` — cannot select a verification key |
| `jwk-not-found` | `kid` does not match any cached JWK (even after force-refresh) |
| `signature-mismatch` | RS256 signature check failed — tampered token or wrong key |
| `expired` | `exp` claim is in the past |
| `bad-issuer` | `iss` claim does not match configured issuer |
| `bad-claim` | Custom claim (`org_id`, `cluster_id`) mismatch — **caller-thrown** |
| `bad-audience` | `aud` claim mismatch — **caller-thrown** |
| `parse-error` | JWT string malformed (base64 / JSON) |

---

## Protecting hand-written code from OpenAPI Generator

OpenAPI Generator writes into `src/main/java/io/tapstate/cloud/sdk/{api,model,auth}/`
and the root package (`ApiClient`, `Configuration`, etc.). Our hand-written
facade lives in `src/main/java/io/tapstate/cloud/sdk/client/` — a package the
generator **never touches**.

`.openapi-generator-ignore` protects these paths from regeneration:

```
pom.xml
README.md
src/main/java/io/tapstate/cloud/sdk/client/
src/test/java/io/tapstate/cloud/sdk/client/
src/test/java/io/tapstate/cloud/sdk/api/
src/test/java/io/tapstate/cloud/sdk/model/
```

---

## (Generated scaffolding — for reference only)

### API Endpoints

| Class | Method | HTTP | Path |
|---|---|---|---|
| *ExchangeApi* | `exchange` | POST | `/v1/auth/exchange` |
| *ResolveExchangeApi* | `jwks` | GET | `/v1/jwks.json` |
| *ResolveExchangeApi* | `resolveCluster` | GET | `/v1/clusters/{clusterId}/resolve` |
| *HealthApi* | `liveness` | GET | `/health` |
| *HealthApi* | `readiness` | GET | `/health/readiness` |
| *ClustersApi* | `createCluster` | POST | `/v1/orgs/{orgId}/clusters` |
| *ClustersApi* | `getCluster` | GET | `/v1/orgs/{orgId}/clusters/{clusterId}` |
| *ClustersApi* | `listClusters` | GET | `/v1/orgs/{orgId}/clusters` |
| *ClustersApi* | `cancelCluster` | POST | `/v1/orgs/{orgId}/clusters/{clusterId}/cancel` |
| *ClustersApi* | `retryCluster` | POST | `/v1/orgs/{orgId}/clusters/{clusterId}/retry` |
| *OrganizationsApi* | `create` | POST | `/v1/orgs` |
| *OrganizationsApi* | `getOrg` | GET | `/v1/orgs/{orgId}` |
| *OrganizationsApi* | `listMyOrganizations` | GET | `/v1/orgs` |
| *RegionsApi* | `listOpenRegions` | GET | `/v1/regions` |
| *RegionsApi* | `getRegion` | GET | `/v1/regions/{code}` |
| *C2StatusReportApi* | `report` | POST | `/v1/clusters/{clusterId}/status-report` |
| *AuthApi* | `login` | POST | `/v1/auth/login` |
| *AuthApi* | `logout` | POST | `/v1/auth/logout` |
| *AuthApi* | `me` | GET | `/v1/auth/me` |
| *AuthApi* | `register` | POST | `/v1/auth/register` |
| *SubscriptionApi* | `getSubscription` | GET | `/v1/orgs/{orgId}/subscription` |
| *SubscriptionApi* | `cancelSubscription` | POST | `/v1/orgs/{orgId}/subscription/cancel` |
| *SubscriptionApi* | `createSubscription` | POST | `/v1/orgs/{orgId}/subscription` |
| *SubscriptionApi* | `transitionStatus` | POST | `/v1/orgs/{orgId}/subscription/status` |

### Authentication schemes

- **session-cookie** (`TAPSTATE_CLOUD_SESSION`) — Cloud Console browser users
- **sdk-static-token** (`Authorization` header) — status sender SDK type ②

---

*README.md is project-owned. The API table above is derived from the
OpenAPI spec and regenerated automatically — rest of this file is maintained
by hand.*
