# ADR-0001: JWKS Rotation Mechanism

| Date | Status |
|------|--------|
| 2026-09-29 | Accepted |

## Status

Accepted

## Context

The Cloud Control Plane issues JWT tokens for cross-organization cluster access. These tokens must be verifiable by external services using a public key distribution mechanism.

### Requirements

1. **Key Persistence**: RSA key pairs must survive Pod restarts to prevent JWT invalidation during rolling updates
2. **Key Rotation**: Must support cryptographic key rotation with overlap period for seamless JWT validation
3. **Distributed Safety**: In multi-instance deployments, only one instance should rotate keys at a time
4. **SDK Compatibility**: Client SDKs must be able to validate JWTs signed by current and previous keys during rotation

## Decision

### Storage

Store key material in MongoDB (same database as other Cloud Control Plane data):

- `JwksKey` document model:
  - `kid`: Key ID (format: `key-YYYY-MM-DD`)
  - `publicKeyPem`: PEM-encoded public key
  - `privateKeyPem`: PEM-encoded private key (optionally encrypted)
  - `status`: `active` or `previous`
  - `createdAt`: Timestamp
  - `expiresAt`: Timestamp

### Rotation Schedule

- **Interval**: 24 hours
- **Execution time**: Midnight UTC (`cron = "0 0 0 * * *"`)
- **Distribution locking**: ShedLock with MongoDB provider

### Key Pool

Maintain two keys in active rotation:

| Status | Purpose | Lifespan |
|--------|---------|----------|
| `active` | Signs new JWTs | 24 hours |
| `previous` | Validates legacy JWTs | 1 hour overlap |

JWKS endpoint (`GET /v1/jwks.json`) returns both keys during overlap period.

### Encryption (Optional)

- AES-256-CTR encryption for private keys at rest
- Controlled via `tapstate.cloud.jwks.encryption-enabled` configuration
- Encryption key provided via environment variable

## Consequences

### Positive

- **Zero downtime rotation**: Existing JWTs remain valid during key transition
- **Recovery**: MongoDB persistence allows instant recovery after Pod restart
- **Compliance**: Meets standard key rotation requirements (SOC2, ISO 27001)
- **Operational safety**: ShedLock prevents duplicate rotation in clustered deployments

### Negative

- **New dependency**: MongoDB storage for key material (already a dependency)
- **Operational complexity**: Requires monitoring of rotation schedule and key state
- **Initial setup**: Need to seed initial key on first deployment

### Risks

- **Encrypted private key loss**: If encryption key is lost, existing keys become unusable
- **Clock skew**: Rotation timing depends on synchronized system time across instances
- **MongoDB availability**: Key rotation cannot proceed if MongoDB is unreachable

## Implementation

### Components

- `JwksKeyRepository` - MongoDB data access
- `JwksRotationService` - Scheduled rotation logic
- `ShedLockConfig` - MongoDB LockProvider setup
- `JwtKeysConfig` - Key loading at startup
- `ResolveController` - JWKS endpoint serving active + previous keys

### Configuration

```yaml
tapstate:
  cloud:
    jwks:
      rotation-enabled: true
      encryption-enabled: false
      encryption-key: ""
```

### Integration

- SDK `JwksKeyCache` already supports multi-key validation (kid-indexed map with TTL-based refresh)

## Changelog

- 2026-09-29: Initial version
  - `JwksKey` model and `JwksKeyRepository` for MongoDB storage
  - `JwksRotationService` with 24h cron, ShedLock distributed lock
  - `ShedLockConfig` with MongoDB LockProvider
  - Updated `JwtKeysConfig` and `ResolveController` to use MongoDB-backed keys
  - Added ShedLock dependency to `pom.xml`
  - `JwksIT` integration tests (5 test cases)
  - Full IT regression passed (7 files, 33 tests)
