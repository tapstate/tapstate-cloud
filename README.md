# Tapstate Cloud — Global Control Plane

Global Control Plane backend for Tapstate Cloud — identity, organization, cluster auto-provisioning, resolve JWT issuance, and SDK status receiver.

## Architecture

Per ADR-0120 / ADR-0121 / ADR-0122 / ADR-0123 / ADR-0124:

- **Framework**: Spring Boot 4.1.x + JDK 21 + Maven (reuses tapstate repo baseline)
- **Database**: MongoDB Atlas (admin-precreated `tapstate-cloud-control` project)
- **Deployment**: EC2 self-hosted k3s (NOT ECS Fargate)
- **Secrets**: k3s Secret (NOT AWS Secrets Manager)
- **Auto-provisioning**: CloudFormation API via AWS SDK v2
- **Container runtime**: Docker (not Hazelcast/Jet/PDK — those are runtime concerns)

## Repository layout

| Path | Responsibility |
|---|---|
| `cloud-control-plane/` | The runnable Spring Boot application: identity, organizations, cluster auto-provisioning, resolve JWT issuance, SDK status receiver, deployment progress, and cluster-version management. |
| `common/` | Shared library (`ApiResponse`, `BaseController`, error codes, filters, exception handling) used by the application. |
| `client/java/` | The published Java SDK, generated from the OpenAPI contract; consumed outside this repository. |
| `client/api/openapi.yaml` | The OpenAPI contract — the external API promise the SDK is generated from. |
| `client/typescript/` | Placeholder for the future TypeScript SDK. |
| `helm/tapstate-cloud-control/` | The Helm chart. It is the only deployment tool for this service. |
| `.github/workflows/` | CI/CD workflows (see [CI/CD](#cicd)). |
| `scripts/` | Operator and deploy helper scripts (`deploy.sh`, `check-*.sh`). |
| `pom.xml` | Maven aggregator/parent: shared versions, dependency and plugin management, and the dependency-ban enforcer rule. |
| `Dockerfile` | Multi-stage (Maven build → JRE 21 runtime) image build for the application. |

## Configuration layers

Configuration is layered; later layers win.

1. **Baseline** — `cloud-control-plane/src/main/resources/application.yml`. Defaults
   and non-sensitive configuration only; every credential entry is an empty
   default that reads from an environment variable.
2. **Profile** — selected with `SPRING_PROFILES_ACTIVE` (default `dev`):
   - `dev` → `application-dev.yml`: local development (mock IaC, in-memory JWKS key).
   - `it` → `application-it.yml`: the integration-test profile (localhost MongoDB,
     fixed development values, faster reconciler interval).
   - `prod`: there is no `application-prod.yml`. The `prod` profile relies on the
     baseline plus the non-sensitive values the Helm chart injects as
     environment variables.
3. **Environment variables** — non-sensitive config comes from the Helm
   ConfigMap; credentials come from k3s Secrets mounted as environment variables
   (`secretKeyRef`). Environment variables override any classpath YAML.

## Quick Start (local development)

```bash
# 1. Build fat jar (aggregator build; the runnable artifact is in cloud-control-plane/target)
mvn clean package -DskipTests

# 2. Run with dev profile (uses local MongoDB)
java -jar cloud-control-plane/target/tapstate-cloud-control-plane.jar --spring.profiles.active=dev

# 3. Or with Docker
docker build -t tapstate-cloud:latest .
docker run -p 8080:8080 \
  -e MONGODB_URI=mongodb://host.docker.internal:27017 \
  tapstate-cloud:latest
```

## API Endpoints

- `GET /health` — liveness probe
- `POST /api/auth/register` — register new user
- `POST /api/auth/login` — login (sets HttpOnly session cookie)
- `GET /api/regions` — list open AWS regions
- `GET /api/orgs/{orgId}/clusters` — list clusters
- `POST /api/orgs/{orgId}/clusters` — create cluster (async auto-provisioning)
- `GET /api/jwks.json` — JWKS public key set
- `POST /api/sdk/status` — SDK C2 status receiver
- `POST /v1/api/webhooks/tawk` — signature-verified Tawk event receiver

The Tawk receiver stores only event IDs and notification routing metadata. It
does not persist chat transcripts, message text, visitor names, or email
addresses. It sends `chat:start`, `ticket:create`, and metadata-only
offline-form callback alerts through the configured Feishu application bot and retries
failed delivery from MongoDB. Configure the Tawk webhook secret and Feishu bot
credentials through the optional k3s Secrets documented in
`helm/tapstate-cloud-control/INSTALL.md`.

OpenAPI docs: `http://localhost:8080/swagger-ui.html`

## OpenAPI SDK Generation

```bash
# Generate Java client from the checked-in OpenAPI contract
mvn -f client/java/pom.xml openapi-generator:generate@generate-java-client -DskipSdkGeneration=false

# Generate TypeScript SDK
mvn openapi-generator:generate@generate-typescript-sdk
```

## Helm Deployment

### Local k3s dev

```bash
helm install tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-local.yaml \
  -n tapstate-cloud-control --create-namespace
```

### AWS production k3s

```bash
# Prerequisites: ops pre-create k3s Secrets first (see helm/tapstate-cloud-control/INSTALL.md)
helm install tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-awsprod.yaml \
  -n tapstate-cloud-control
```

## CI/CD

**Triggers**

- **Pull request / any branch push** → `.github/workflows/ci.yaml`: compile,
  package, unit tests, OpenAPI check, Helm lint, and a no-push Docker build.
- **Push to `main`** → `.github/workflows/deploy.yaml` deploys to **awsdev**.
- **Push to `release-v*`** → `.github/workflows/deploy.yaml` deploys to
  **production**.
- **Full Integration** (`.github/workflows/full-integration.yml`) runs only while
  the repository variable `FULL_INTEGRATION_ENABLED` is exactly `"true"` and the
  push/PR touches its trigger paths.

**Gating.** The awsdev deploy waits for the CI run of the *same pushed SHA* to
pass, and for the Full Integration run only while that switch is on and the push
touches its trigger paths. Nothing deploys from a red or not-yet-finished commit.

**Deployment tooling.** Helm is the only deployment tool. The image is built and
pushed to ECR tagged with the commit SHA, then applied with `helm upgrade --install`.
There is no `kubectl rollout restart` step; the chart's `RollingUpdate` strategy
handles rollout.

**Automatic vs. manual**

| Concern | Owner |
|---|---|
| Build, unit tests, integration tests, OpenAPI/Helm lint | **Automatic** (CI) |
| Docker image build and ECR push (tagged by commit SHA) | **Automatic** (CD) |
| Application deployment (`helm upgrade`) to awsdev on `main` | **Automatic** (CD, gated by CI) |
| Application deployment to production on `release-v*` | **Automatic** (CD) |
| ECR pull-secret refresh in the target namespace | **Automatic** (CD) |
| Java SDK publish to Nexus | **Automatic** (CD) |
| k3s cluster and namespaces (`tapstate`, `tapstate-cloud-control`) | **Manual** (ops) |
| k3s Secrets (see below) | **Manual** (ops, pre-created before install) |
| DNS A record (e.g. `cloud.tapstate.com`) | **Manual** (ops) |
| TLS certificate issuance and renewal (nginx edge) | **Manual** (ops) |
| CloudWatch log groups and alarms | **Manual** (ops) |

## Environments and credentials

Two deployed environments share one ECR repository (region `ap-east-1`); images
are tagged by commit SHA, so the same immutable artifact is deployed by both.

| | awsdev | production |
|---|---|---|
| Trigger | push to `main` | push to `release-v*` |
| Namespace | `tapstate` | `tapstate-cloud-control` |
| Helm values | `values-awsdev.yaml` | `values-awsprod.yaml` |
| k3s shape | single-node EC2 | multi-node, nginx edge terminating TLS |
| Replicas | 1 | 2 |

**k3s Secrets** (pre-created by ops; the chart never creates them). Required and
optional entries, with their keys:

- `tapstate-cloud-control-mongo-uri` (`mongo-uri`) — **required**
- `tapstate-cloud-control-jwks-signing-key` (`signing-key`) — **required**
- `tapstate-cloud-control-aws-credentials` (`access-key-id`, `secret-access-key`) — **required**
- `tapstate-cloud-control-dns` (`api-key`, `api-secret`) — required when `iacMode=real`
- `tapstate-cloud-control-iac` (`master-key`) — required when `iacMode=real`
- `tapstate-cloud-control-jwks-encryption-key` (`encryption-key`) — optional
- `tapstate-cloud-control-runtime-atlas-uri` (`atlas-uri`) — optional
- `tapstate-cloud-control-sample-sources` (`password`) — optional
- `tapstate-cloud-control-cluster-version` (`release-token`) — optional

The `tapstate-ecr-pull` image-pull secret in each namespace is refreshed by the CD
workflow on every deploy.

**Credentials never live in this repository or in logs.** Real values are created
on the cluster only; the repository carries placeholders (`${ENV_VAR}`,
`changeme`, `your-...`, `example`, `placeholder`). No credential is ever printed
into a log line, an error message, or a CI step summary. See
`CONTRIBUTING.md` for the full policy.

## Contract changes

The contract surface — the fixtures under
`cloud-control-plane/src/main/resources/contract-fixtures/`, the OpenAPI file
`client/api/openapi.yaml`, and the published SDK `client/java/` — is reviewed by
both the provider and the consumer. Change the contract before the
implementation, and version any breaking change. The full procedure is in
[`CONTRIBUTING.md`](CONTRIBUTING.md#contract-changes).

## Repository Rules

- **Public repo, zero CJK** (same as tapstate repo — enforces by `repo-hygiene` CI gate)
- **No Hazelcast/Jet/PDK/Tapstate runtime deps** — those are per-Cluster runtime concerns (ADR-0121 D6)
- **No agent artifacts** — same zero-footprint rule as tapstate repo
