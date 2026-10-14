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

## Quick Start (local development)

```bash
# 1. Build fat jar
mvn clean package -DskipTests

# 2. Run with dev profile (uses local MongoDB)
java -jar target/tapstate-cloud.jar --spring.profiles.active=dev

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

- **PR baseline**: `.github/workflows/ci.yaml` — lint → compile → test → helm lint
- **push main**: `.github/workflows/deploy.yaml` — docker build → ECR push → `kubectl rollout restart`

## Repository Rules

- **Public repo, zero CJK** (same as tapstate repo — enforces by `repo-hygiene` CI gate)
- **No Hazelcast/Jet/PDK/Tapstate runtime deps** — those are per-Cluster runtime concerns (ADR-0121 D6)
- **No agent artifacts** — same zero-footprint rule as tapstate repo
