# ADR-0002: Cloud Authentication

**Status:** Accepted  
**Date:** 2026-09-29  
**Decides:** Issue #14: logout should fire JTI revocation BEFORE invalidating session

## Context

The Cloud Control Plane session-based authentication requires a logout flow that:

1. Fires signed JTI revocation callbacks to all active JWTs for the user
2. Then invalidates the Cloud-side session

Currently, the session is invalidated BEFORE the revocation callbacks, causing `CurrentUser.getEmail()` to return null and skipping the revocation flow entirely.

## Decision

The `AuthController.logout()` method must:

1. **Capture the current user email first** (via `CurrentUser.getEmail()`)
2. **Fire JTI revocation callbacks** (via `revokeAllActiveJwts(userEmail)`)
3. **Then invalidate the session** (via `session.invalidate()`)

This ordering ensures:
- Revocation callbacks are sent with the correct user email
- All active resolve JWTs are properly notified
- Users can be securely signed out across all devices

## Consequences

**Positive:**
- Users are securely logged out from all sessions
- Cluster callbacks are properly notified of user sign-out
- Aligns with RFC 7517 JWK revocation patterns

**Negative:**
- None

**Neutral:**
- The session cookie is still cleared after callbacks are sent (browser-side cleanup)

## Changelog

- 2026-09-29: Initial implementation (Issue #14, PR #17)
