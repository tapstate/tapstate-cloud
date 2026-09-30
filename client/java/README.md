# TapState Cloud — Control Plane SDK

Maven artifact: `io.tapstate:cloud-control-plane-sdk`

A small, thread-safe Java library for Cluster runtimes (Pipeline Runtime, Cluster
Console) to talk to the Cloud Global Control Plane. Three high-level facade methods
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

The SDK is built with sensible defaults — `CLUSTER_ID` is picked up from the
environment automatically; the static token and base URL must be set explicitly
(per-Cluster secret, no safe global default).

| Env var | Required | Where used |
|---|---|---|
| `CLUSTER_ID` | Only `exchangeForJwt()` + `reportStatus()` | Cluster UUID, sent as `cluster_id` in exchange request and path param in C2 |

Set `sdkStaticToken` via builder — it's the per-Cluster service identity secret
(`Cluster.sdkStaticToken` at provisioning time) used for **both** exchange and
C2 status-report auth. Never commit it; inject from your deployment's secret
store.

Common builder options:

```java
CloudControlPlaneSdk sdk = CloudControlPlaneSdk.builder()
    .baseUrl("https://cloud.tapstate.io")          // required — GC base URL
    .issuer("https://cloud.tapstate.io")           // default — GC's JWT signer; change if self-hosted
    .sdkStaticToken(System.getenv("SDK_STATIC_TOKEN"))  // required for exchangeForJwt() and reportStatus()
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
    // cpe.getErrorCode()   → e.g. "exchange.cluster-identity-mismatch"
    // cpe.getHttpStatus()   → 400 / 401 / 500 / 0 (network)
    // cpe.getServerMessage() → GC's English error text
    // Handle by showing the user a retry / contact admin message
}
```

Throws typed `CloudControlPlaneException` — NOT raw `ApiException`.
Common error codes:

| code | http | meaning |
|---|---|---|
| `exchange.cluster-identity-mismatch` | 401 | `sdkStaticToken` doesn't match GC's record for this Cluster |
| `exchange.code-expired` | 401 | exchange_code's 60-second TTL ran out |
| `exchange.code-consumed` | 401 | exchange_code was already redeemed by another request (race) |
| `sdk.not-configured` | 0 | `sdkStaticToken` or `clusterId` missing on SDK |
| `sdk.empty-response` | 200 | GC returned success envelope but JWT payload is null |
| `sdk.invalid-expires-at` | 200 | GC returned unparseable `expiresAt` string |
| `sdk.http-N` | N | Fallback — GC returned non-2xx without a domain code |

---

## Function 2 — offline JWKS JWT verification (ADR-0122 D5 / ADR-0125 D6)

After the initial exchange, every subsequent workload request carries the resolve
JWT in `Authorization: Bearer`. The Cluster Server must **never call GC online**
for per-request auth — JWKS is cached locally.

```java
try {
    VerifiedJwt v = sdk.verifyJwt(bearerToken);
    // v.userId()       → stable user UUID (custom claim)
    // v.userEmail()     → JWT sub — who
    // v.orgId()         → cross-check: does this JWT belong to OUR org?
    // v.clusterId()     → cross-check: does this JWT belong to OUR cluster?
    // v.audience()      → expected Cluster audience (<clusterId>.api.tapstate.io)
    // v.scope()         → List<String>, e.g. ["workload:read", "workload:write"]
    // v.issuer()        → should equal the configured issuer
    // v.expiresAt()     → Instant
    // v.jti()           → for replay detection (if needed)
} catch (JwtVerificationException ex) {
    switch (ex.getKind()) {
        case "missing-kid"        → reject — malformed token header
        case "jwk-not-found"      → log.warn("GC key rotation, will refresh on next request")
        case "signature-mismatch" → reject as tampered token
        case "expired"            → reject — send client back to Cluster Console for re-exchange
        case "bad-issuer"         → reject — token signed by wrong GC
        case "parse-error"        → reject — malformed base64/JSON
        default                   → reject — unknown failure
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

All `JwtVerificationException` subtypes carry both a `getKind()` string for
convenient branching and are distinct classes for `instanceof` checks.

| `getKind()` | Meaning | SDK throws? |
|---|---|---|
| `missing-kid` | JWT header has no `kid` — cannot select a verification key | ✅ |
| `jwk-not-found` | `kid` does not match any cached JWK (even after force-refresh) | ✅ |
| `signature-mismatch` | RS256 signature check failed — tampered token or wrong key | ✅ |
| `expired` | `exp` claim is in the past | ✅ |
| `bad-issuer` | `iss` claim does not match configured issuer | ✅ |
| `parse-error` | JWT string malformed (base64 / JSON) | ✅ |
| `bad-claim` | Custom claim (`org_id`, `cluster_id`) mismatch | ❌ caller-thrown |
| `bad-audience` | `aud` claim mismatch | ❌ caller-thrown |

---

## Function 3 — C2 status report (runtime → Global Control Plane)

The runtime calls this periodically (~30s) for GC-side monitoring. Uses the
same `sdkStaticToken` for authentication — **never** a resolve JWT — so C2
failures do not block Function 2 workload auth.

### Bounded retry policy

Up to **3 attempts** with exponential backoff (500 ms initial, 2× multiplier).
Only retries **5xx and connection failures** (`code == 0`); **4xx errors are
terminal** — retry would never help (invalid token, cluster mismatch, nonce replay).

```java
try {
    sdk.reportStatus(
        UUID.randomUUID().toString(),  // nonce — unique per call
        "2.1.0",                        // runtimeVersion
        uptimeMs,                       // milliseconds since runtime process started
        activePipelineCount,            // currently-running pipelines
        lastErrorAtIso                  // ISO-8601 of last runtime error, or null
    );
} catch (CloudControlPlaneException cpe) {
    switch (cpe) {
        case CloudControlPlaneException.C2TokenMissing e ->
            // sdkStaticToken was never configured — deploy misconfiguration
            log.error("C2 token missing: {}", e.getMessage());
        case CloudControlPlaneException.C2AuthFailed e ->
            // GC returned 401/403 — token invalid, cluster revoked
            log.error("C2 auth failed: {}", e.getMessage());
        case CloudControlPlaneException.C2NonceReplay e ->
            // GC returned 409 — same nonce used twice — regenerate UUID for next call
            log.warn("C2 nonce replay: {}", e.getMessage());
        case CloudControlPlaneException.C2Transient e ->
            // 3 retries exhausted — GC may be unreachable; skip this heartbeat
            log.warn("C2 transient failure: {}", e.getMessage());
    }
}
```

---

## verifyRequest — Cloud → Cluster callback RS256 signature verification

GC calls the Cluster back on revocation / admin-triggered lifecycle transitions.
These callbacks carry no JWT — instead GC signs a canonical string with the
active JWKS key. The Cluster verifies offline using its cached JWKS.

Canonical signature input (MUST match GC exactly):

```
method + "|" + ts + "|" + nonce + "|" + data
```

- `method` — HTTP method (e.g. `"POST"`)
- `ts` — timestamp from query param (epoch ms string)
- `nonce` — random string from query param
- `data` — request body, or `jti` for bodyless revocation callbacks

Signature algorithm: RS256, same key pair GC uses for resolve JWT signing.

```java
// From a Spring Controller handling Cloud callbacks:
@GetMapping("/cloud-callback")
public ResponseEntity<Void> handleCallback(
        @RequestParam String ts,
        @RequestParam String nonce,
        @RequestParam String sign,
        HttpServletRequest request) {

    String method = request.getMethod();
    String data = request.getParameter("jti");  // or read body for POST callbacks

    boolean ok = sdk.verifyRequest(method, ts, nonce, data, sign);
    if (!ok) {
        // Try force-refresh — GC may have just rotated keys
        return ResponseEntity.status(401).build();
    }
    // ... handle the callback
}
```

Returns `false` if the JWKS cache is empty or **no** cached key verifies the
signature. During GC key-rotation overlap (old + new keys both in cache), the
SDK tries every key — whichever matches wins.

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
| *ExchangeApi* | `exchange` | POST | `/v1/api/auth/exchange` |
| *ResolveExchangeApi* | `jwks` | GET | `/v1/api/jwks.json` |
| *ResolveExchangeApi* | `resolveCluster` | GET | `/v1/api/clusters/{clusterId}/resolve` |
| *HealthApi* | `liveness` | GET | `/health` |
| *HealthApi* | `readiness` | GET | `/health/readiness` |
| *ClustersApi* | `getCluster` | GET | `/v1/api/orgs/{orgId}/clusters/{clusterId}` |
| *ClustersApi* | `listClusters` | GET | `/v1/api/orgs/{orgId}/clusters` |
| *ClustersApi* | `retryCluster` | POST | `/v1/api/orgs/{orgId}/clusters/{clusterId}/retry` |
| *OrganizationsApi* | `create` | POST | `/v1/api/orgs` |
| *OrganizationsApi* | `getOrg` | GET | `/v1/api/orgs/{orgId}` |
| *OrganizationsApi* | `listMyOrganizations` | GET | `/v1/api/orgs` |
| *RegionsApi* | `listOpenRegions` | GET | `/v1/api/regions` |
| *RegionsApi* | `getRegion` | GET | `/v1/api/regions/{code}` |
| *C2StatusReportApi* | `report` | POST | `/v1/api/clusters/{clusterId}/status-report` |
| *AuthApi* | `login` | POST | `/v1/api/auth/login` |
| *AuthApi* | `logout` | POST | `/v1/api/auth/logout` |
| *AuthApi* | `me` | GET | `/v1/api/auth/me` |
| *AuthApi* | `register` | POST | `/v1/api/auth/register` |
| *SubscriptionApi* | `getSubscription` | GET | `/v1/api/orgs/{orgId}/subscription` |
| *SubscriptionApi* | `cancelSubscription` | POST | `/v1/api/orgs/{orgId}/subscription/cancel` |
| *SubscriptionApi* | `createSubscription` | POST | `/v1/api/orgs/{orgId}/subscription` |
| *SubscriptionApi* | `transitionStatus` | POST | `/v1/api/orgs/{orgId}/subscription/status` |

### Authentication schemes

- **session-cookie** (`TAPSTATE_CLOUD_SESSION`) — Cloud Console browser users
- **sdk-static-token** — SDK's `sdkStaticToken` used as `X-Cluster-Identity-Secret` header on exchange, `Authorization: Bearer` on C2

---

*README.md is project-owned. The API table above is derived from the
OpenAPI spec and regenerated automatically — rest of this file is maintained
by hand.*
