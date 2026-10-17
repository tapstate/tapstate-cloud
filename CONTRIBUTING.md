# Contributing to tapstate-cloud

This repository holds the Tapstate Cloud Global Control Plane: the application
(`cloud-control-plane/`), its shared library (`common/`), the published Java SDK
(`client/java/`), and the Helm chart (`helm/`). These are the local rules for
changing it. They are narrower than — and do not replace — the guidelines of any
workspace this repository is checked out in.

## Ownership and CODEOWNERS

`CODEOWNERS` at the repository root is the source of truth for review ownership.

- **Every production path has a named owner.** `cloud-control-plane/`, `common/`,
  `helm/`, `Dockerfile`, `/pom.xml`, and the CI workflows are each owned by a
  named team or user. The `*` fallback exists only as a safety net; it does not
  make a production path "owned".
- **The shared contract surface is owned by both sides of the contract.** The
  contract fixtures (`cloud-control-plane/src/main/resources/contract-fixtures/`),
  the published SDK (`client/java/`), and the OpenAPI contract
  (`client/api/openapi.yaml`) each list two owners — the provider (Control Plane)
  and the consumer (runtime/Console clients). A change there is reviewed from
  both directions.
- **Owners must exist.** A CODEOWNERS handle may only be a team or user that
  exists in the organization. A non-existent handle silently disables the rule
  (no review is requested and no merge is blocked), which is worse than no rule
  at all. `scripts/check-repo-structure.sh` fails on any handle outside the
  allowed set.

Update `CODEOWNERS` in the same change that introduces a new production path or
a new contract surface.

## Language

All repository content is written in English: code, comments, commit messages,
pull-request descriptions, documentation, Helm values, and CI workflow text.
There is no exemption for tests, fixtures, or resource bundles.

## Contract changes

The contract is an external promise, not an implementation detail. Change it in
this order:

1. **Contract first.** Edit `cloud-control-plane/src/main/resources/contract-fixtures/`
   and/or the OpenAPI contract `client/api/openapi.yaml` before touching the
   implementation. The fixtures and the OpenAPI file are the checked-in source
   of truth.
2. **Both sides review.** Because the contract surface is owned by the provider
   and the consumer together, a contract change requires review from both.
3. **Implementation second.** Land the providing code, the generated SDK, and
   any consumer side against the already-agreed contract.
4. **Breaking changes need a version.** Removing or changing an existing field,
   endpoint, or status code is a breaking change to a published promise. It is
   never done in place: add a new version (`...-v2`) and keep the old one until
   the consumers have migrated.

## Dependencies and architecture boundaries

- **No compile-time dependency on the Tapstate runtime repository or the PDK.**
  Coordinates under `io.tapdata.*` (the PDK) and the Tapstate runtime artifacts
  must never be declared here — this is a standalone control plane, and those
  are per-Cluster runtime concerns. The build enforces this: `maven-enforcer-plugin`
  runs a `bannedDependencies` rule at the `validate` phase and fails the build if
  such a coordinate appears. Only this repository's own `io.tapstate` modules are
  allowed.
- **Module dependency direction is one-way:** `common` is a library; the runnable
  application (`cloud-control-plane`) depends on it. `common` never depends on
  `cloud-control-plane`.
- **`client/java/` publishes the SDK.** It is a consumer-facing artifact; it does
  not depend on the application or on `common`, and it is not deployed as an
  application.

## Secrets

- **Secrets live in k3s Secrets, nowhere else.** Every credential the application
  needs (MongoDB URI, JWKS signing key, AWS credentials, DNS and IaC keys) is
  injected from a pre-created k3s Secret via the Helm chart. The chart does not
  create the Secrets.
- **Never commit a secret.** No private key, access key, password, token, or
  connection string with embedded credentials belongs in this repository —
  including tests and fixtures. Use placeholders instead: `${ENV_VAR}` in
  configuration, `changeme` / `your-...` / `example` / `placeholder` / `dummy` in
  examples and documentation, and generate real values on the cluster.
- **Never log credentials.** Do not print, echo, or interpolate a credential into
  a log line, an error message, or a CI step summary.
- `scripts/check-no-secrets.sh` scans the working tree and fails on PEM private
  keys, AWS access key ids, password-like assignments, and long secret-like
  strings. It prints only `file:line` and a category, never the matched value. A
  line that must be exempt (a known dev-only constant) is marked inline with
  `nosecret`.

## Commits and branches

- Work on a `ws/<workstream-id>` branch and deliver it as a single pull request.
  The PR opens as a draft and is merged by a human reviewer.
- **No attribution footers.** Do not add `Co-Authored-By`, tool or generator
  signatures, or any "generated with ..." line to a commit message. Authorship
  comes from git configuration, not the message body.
- Keep a commit message focused on why the change is made.
