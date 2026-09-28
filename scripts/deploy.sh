#!/usr/bin/env bash
# ======================================================================
# Tapstate Cloud — Build + Push to Harbor + Deploy to k3s
#
# Usage:
#   scripts/deploy.sh <env>              # build + push + helm deploy (default)
#   scripts/deploy.sh <env> build-only   # only mvn + docker push image
#   scripts/deploy.sh <env> deploy-only  # only helm deploy (image assumed already pushed)
#
# Env tables (add more as test/prod come online):
#   dev   → Harbor (harbor.internal.tapdata.io) → k3s (tapserver179/193)
#   test  → TBD
#   prod  → TBD
# ======================================================================
set -euo pipefail
IFS=$'\n\t'

# ----------------------------------------------------------------------
# Argument parsing
# ----------------------------------------------------------------------
ENV_NAME="${1:-}"
MODE="${2:-all}"  # all | build-only | deploy-only

case "$MODE" in
  all|build-only|deploy-only) ;;
  *) echo "Usage: $0 <dev|test|prod> [all|build-only|deploy-only]" >&2; exit 1 ;;
esac

if [ -z "$ENV_NAME" ] || [ "${ENV_NAME:0:1}" = "-" ]; then
  echo "Usage: $0 <dev|test|prod> [all|build-only|deploy-only]" >&2
  exit 1
fi

# ----------------------------------------------------------------------
# Env-specific configuration (one row per env, add test/prod later)
# ----------------------------------------------------------------------
case "$ENV_NAME" in
  dev)
    NEXUS_HOST="harbor.internal.tapdata.io"
    NEXUS_REPO="tapstate-cloud/tapstate-cloud-control"
    SSH_HOST="113.98.206.142"          # Gateway server with Docker + Harbor reachable
    SSH_USER="lg"
    K3S_CONTROL_PLANE_HOST="113.98.206.138"   # tapserver193 (root SSH access, has k3s)
    K3S_CONTROL_PLANE_NODE="tapserver193"     # Where pod runs (insecure Harbor registries.yaml)
    K3S_NAMESPACE="tapstate"
    K3S_CLUSTER_IP="192.168.1.193"            # Private IP of k3s API server (behind tunnel)
    KUBECONFIG_PATH="${HOME}/.kube/k3s-tunnel.yaml"  # Generated at runtime — SSH tunnel needed
    HELM_VALUES_FILE="values-dev.yaml"
    USE_ECR=false
    ;;
  awsdev)
    NEXUS_HOST="263186327888.dkr.ecr.ap-east-1.amazonaws.com"
    NEXUS_REPO="tapstate-cloud/tapstate-cloud-control"
    BUILD_HOST="lg@113.98.206.142"       # Gateway with Docker — same as dev
    SSH_HOST="ec2"                       # aliased to 18.166.228.163 in ~/.ssh/config
    SSH_USER="ubuntu"
    K3S_CONTROL_PLANE_HOST="ec2"         # Same — single-node k3s on EC2
    K3S_CONTROL_PLANE_NODE="ip-172-31-4-81"
    K3S_NAMESPACE="tapstate"
    K3S_CLUSTER_IP="172.31.4.81"         # Private IP of EC2 k3s (direct, no tunnel needed)
    KUBECONFIG_PATH="${HOME}/.kube/k3s-awsdev.yaml"  # Direct EC2 kubeconfig copy
    HELM_VALUES_FILE="values-awsdev.yaml"
    USE_ECR=true
    ;;
  test)
    echo "ERROR: test env not configured yet — fill in deploy.sh" >&2; exit 1 ;;
  prod)
    echo "ERROR: prod env not configured yet — fill in deploy.sh" >&2; exit 1 ;;
  *)
    echo "ERROR: unknown env '$ENV_NAME'" >&2; exit 1 ;;
esac

if [ -z "$NEXUS_HOST" ]; then echo "ERROR: $ENV_NAME NEXUS_HOST missing" >&2; exit 1; fi
if [ -z "$SSH_HOST" ]; then echo "ERROR: $ENV_NAME SSH_HOST missing" >&2; exit 1; fi
if [ -z "$K3S_CONTROL_PLANE_NODE" ]; then echo "ERROR: $ENV_NAME K3S nodeSelector missing" >&2; exit 1; fi
if [ -z "$HELM_VALUES_FILE" ]; then echo "ERROR: $ENV_NAME HELM_VALUES missing" >&2; exit 1; fi

SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_rsa}"
SSH_KEY_ROOT="${SSH_KEY_ROOT:-$SSH_KEY}"        # Same key for root SSH to k3s control-plane
GATEWAY="${SSH_USER}@${SSH_HOST}"
BUILD_HOST="${BUILD_HOST:-$GATEWAY}"            # Where Docker builds happen — defaults to GATEWAY
CP_ROOT="root@${K3S_CONTROL_PLANE_HOST}"
TIMESTAMP="$(date +%Y%m%d-%H%M)"
GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

# Image tag: auto-generated for all/build-only; default to <env>-latest for deploy-only (override via TAG env)
if [ -n "${TAG:-}" ]; then
  IMAGE_TAG="$TAG"
elif [ "$MODE" = "deploy-only" ]; then
  IMAGE_TAG="${ENV_NAME}-latest"
else
  IMAGE_TAG="${ENV_NAME}-${TIMESTAMP}-${GIT_SHA}"
fi
REMOTE_IMAGE="${NEXUS_HOST}/${NEXUS_REPO}:${IMAGE_TAG}"
REMOTE_IMAGE_LATEST="${NEXUS_HOST}/${NEXUS_REPO}:${ENV_NAME}-latest"
RELEASE_NAME="tapstate-cloud-control"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HELM_CHART_DIR="${PROJECT_ROOT}/helm/tapstate-cloud-control"
HELM_VALUES_PATH="${HELM_CHART_DIR}/${HELM_VALUES_FILE}"
JAR_PATH="${PROJECT_ROOT}/cloud-control-plane/target/tapstate-cloud-control-plane.jar"
REMOTE_BUILD_DIR="/home2/${SSH_USER}/tapstate-cloud-build"
TUNNEL_PORT=6443

HEADER="\033[1;36m"
OK="\033[1;32m"
WARN="\033[1;33m"
ERR="\033[1;31m"
RESET="\033[0m"
log()    { echo -e "${HEADER}[$(date +%H:%M:%S)]${RESET} $*"; }
log_ok() { echo -e "${OK}[$(date +%H:%M:%S)] OK ${RESET}$*"; }
log_warn(){ echo -e "${WARN}[$(date +%H:%M:%S)] WARN ${RESET}$*"; }
log_err(){ echo -e "${ERR}[$(date +%H:%M:%S)] ERR ${RESET}$*"; }

# ----------------------------------------------------------------------
# Pre-checks
# ----------------------------------------------------------------------
require_cmd() { command -v "$1" >/dev/null 2>&1 || { log_err "Missing: $1"; exit 1; }; }

log "Environment:     $ENV_NAME"
log "Image (tagged):  $REMOTE_IMAGE"
log "Image (latest):  $REMOTE_IMAGE_LATEST"
log "Harbor:          $NEXUS_HOST/$NEXUS_REPO"
log "SSH gateway:      $GATEWAY"
log "k3s control-plane: $K3S_CONTROL_PLANE_HOST → node $K3S_CONTROL_PLANE_NODE"
log "Namespace:       $K3S_NAMESPACE"
echo

require_cmd scp
require_cmd ssh
require_cmd kubectl
require_cmd helm

if [ "$MODE" != "deploy-only" ]; then
  require_cmd mvn
fi

if [ ! -f "$HELM_VALUES_PATH" ]; then
  log_err "Helm values file not found: $HELM_VALUES_PATH"
  exit 1
fi

# ----------------------------------------------------------------------
# k3s kubeconfig setup — tunnel for dev, direct copy for awsdev
# ----------------------------------------------------------------------
setup_k3s_tunnel() {
  if [ "$USE_ECR" = "true" ]; then
    # awsdev: EC2 is reachable directly, copy kubeconfig
    log "Copying EC2 kubeconfig (direct access)..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -i "$SSH_KEY" "$GATEWAY" "sudo cat /etc/rancher/k3s/k3s.yaml" > "$KUBECONFIG_PATH"
    # Replace localhost with EC2 private IP
    sed -i.bak "s|server: https://127.0.0.1:6443|server: https://${K3S_CLUSTER_IP}:6443|" "$KUBECONFIG_PATH"
    rm -f "${KUBECONFIG_PATH}.bak"
    log_ok "kubeconfig → $KUBECONFIG_PATH (direct)"
  else
    # dev: need SSH tunnel through gateway server
    log "Setting up SSH tunnel for k3s API server..."
    kill_port_used "$TUNNEL_PORT" || true
    ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -fNL "${TUNNEL_PORT}:${K3S_CLUSTER_IP}:6443" "$GATEWAY" 2>&1
    for i in $(seq 1 10); do
      if nc -z 127.0.0.1 "$TUNNEL_PORT" 2>/dev/null; then break; fi
      sleep 1
    done
    local admin_kubeconfig
    admin_kubeconfig=$(ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
                          -i "$SSH_KEY_ROOT" "$CP_ROOT" "cat /etc/rancher/k3s/k3s.yaml" 2>/dev/null)
    if [ -z "$admin_kubeconfig" ]; then
      log_err "Cannot fetch k3s admin kubeconfig from $CP_ROOT. Check root SSH key."
      exit 1
    fi
    echo "$admin_kubeconfig" \
      | sed "s|server: https://127.0.0.1:6443|server: https://127.0.0.1:${TUNNEL_PORT}|" \
      | sed '/certificate-authority-data:/d' \
      > "$KUBECONFIG_PATH"
    python3 -c "
import yaml, sys
cfg = yaml.safe_load(open('$KUBECONFIG_PATH'))
for c in cfg.get('clusters', []):
    c['cluster']['insecure-skip-tls-verify'] = True
yaml.safe_dump(cfg, open('$KUBECONFIG_PATH', 'w'), sort_keys=False)
" 2>/dev/null || {
    sed -i '' 's|server: https://127.0.0.1:'"${TUNNEL_PORT}"'|server: https://127.0.0.1:'"${TUNNEL_PORT}"'\n      insecure-skip-tls-verify: true|' "$KUBECONFIG_PATH"
  }
    log_ok "k3s tunnel ready → https://127.0.0.1:${TUNNEL_PORT}"
  fi

  if ! kubectl --kubeconfig="$KUBECONFIG_PATH" cluster-info >/dev/null 2>&1; then
    log_err "k3s cluster unreachable. Check SSH to $GATEWAY."
    kubectl --kubeconfig="$KUBECONFIG_PATH" cluster-info 2>&1 | tail -3
    exit 1
  fi
  log_ok "k3s cluster reachable"
}

kill_port_used() {
  local port=$1
  local pids
  pids=$(lsof -ti ":${port}" 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "$pids" | xargs kill 2>/dev/null || true
  fi
}

cleanup() {
  # Keep tunnel alive between runs — only kill port if it's our own tunnel
  local pid
  pid=$(lsof -ti ":${TUNNEL_PORT}" 2>/dev/null || true)
  if [ -n "$pid" ]; then
    # Check if this is our ssh tunnel (command line contains -fNL)
    if ps -p "$pid" -o command= 2>/dev/null | grep -q "ssh.*-fNL.*${TUNNEL_PORT}"; then
      kill "$pid" 2>/dev/null || true
    fi
  fi
}
# Don't trap EXIT — let tunnel persist so the next step/script can reuse it
# trap cleanup EXIT

# ----------------------------------------------------------------------
# Pre-flight: ensure k3s node has insecure Harbor registry config
# ----------------------------------------------------------------------
ensure_k3s_registries() {
  log "Ensuring k3s containerd insecure Harbor registries.yaml on $K3S_CONTROL_PLANE_NODE..."
  ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
      -i "$SSH_KEY_ROOT" "$CP_ROOT" "
    mkdir -p /etc/rancher/k3s
    cat > /etc/rancher/k3s/registries.yaml << 'REGEOF'
mirrors:
  docker.io:
    endpoint:
      - \"https://docker.m.daocloud.io\"
configs:
  \"harbor.internal.tapdata.io\":
    tls:
      insecure_skip_verify: true
  \"192.168.1.184\":
    tls:
      insecure_skip_verify: true
REGEOF
    # Also ensure /etc/hosts entry (DNS mismatch from corporate DNS)
    grep -q '192.168.1.184 harbor.internal.tapdata.io' /etc/hosts || \
      echo '192.168.1.184 harbor.internal.tapdata.io' >> /etc/hosts
    # Restart k3s if this was fresh
    if ! grep -q 'harbor.internal.tapdata.io' /etc/rancher/k3s/registries.yaml.bak 2>/dev/null; then
      cp /etc/rancher/k3s/registries.yaml /etc/rancher/k3s/registries.yaml.bak
      systemctl restart k3s 2>/dev/null || true
      sleep 5
    fi
    echo OK
  "
  log_ok "k3s insecure registry configured"
}

# ----------------------------------------------------------------------
# Step 1 — Maven build (skip for deploy-only)
# ----------------------------------------------------------------------
build_jar() {
  log "Step 1: Maven package..."
  pushd "$PROJECT_ROOT" >/dev/null
  mvn -q -B clean package -DskipTests -pl cloud-control-plane -am
  popd >/dev/null
  JAR_SIZE=$(du -h "$JAR_PATH" | awk '{print $1}')
  log_ok "Jar built: $JAR_PATH ($JAR_SIZE)"
}

# ----------------------------------------------------------------------
# Step 2 — Copy jar + runtime Dockerfile to remote, build + push
#   dev     → 113 Docker → Harbor
#   awsdev  → 113 Docker → docker save → scp local → retag → push ECR (no ECR push on remote — no AWS creds)
# ----------------------------------------------------------------------
push_image() {
  log "Step 2: Prepare remote build dir on $BUILD_HOST"
  ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" "mkdir -p $REMOTE_BUILD_DIR"

  log "Step 3: Copy jar to remote ($(du -h "$JAR_PATH" | awk '{print $1}'))"
  scp -o IdentitiesOnly=yes -i "$SSH_KEY" "$JAR_PATH" "${BUILD_HOST}:${REMOTE_BUILD_DIR}/tapstate-cloud-control-plane.jar"

  log "Step 4: Create runtime Dockerfile on remote"
  ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" "cat > ${REMOTE_BUILD_DIR}/Dockerfile.runtime << 'DOCKEREOF'
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY tapstate-cloud-control-plane.jar ./app.jar
ENV JAVA_OPTS=\"-Xms256m -Xmx512m -XX:+UseContainerSupport\"
EXPOSE 8080
ENTRYPOINT java -jar /app/app.jar
DOCKEREOF"

  log "Step 5: Build Docker image on remote → $REMOTE_IMAGE"
  ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" "
    cd $REMOTE_BUILD_DIR
    docker build -t '$REMOTE_IMAGE' -t '$REMOTE_IMAGE_LATEST' -f Dockerfile.runtime . 2>&1 | tail -5
  "
  log_ok "Image built on remote"

  if [ "$USE_ECR" = "true" ]; then
    # ECR path: no AWS creds on remote → save tar, pull locally, retag, push
    require_cmd docker
    log "Step 6a: docker save on remote → ECR push locally"
    local ECR_IMAGE="${NEXUS_HOST}/${NEXUS_REPO}"
    local TAR_FILE="/tmp/tapstate-cloud-image.tar"
    ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" \
      "docker save '$REMOTE_IMAGE_LATEST' -o ${REMOTE_BUILD_DIR}/image.tar 2>&1 | tail -3"
    scp -o IdentitiesOnly=yes -i "$SSH_KEY" \
      "${BUILD_HOST}:${REMOTE_BUILD_DIR}/image.tar" "$TAR_FILE"
    ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" "rm -rf $REMOTE_BUILD_DIR"
    docker load -i "$TAR_FILE" 2>&1 | tail -2
    rm -f "$TAR_FILE"

    # ECR has IMMUTABLE tags — always use $IMAGE_TAG from env or auto-generated
    local tag="${IMAGE_TAG:-awsdev-$(date +%Y%m%d-%H%M)}"
    IMAGE_TAG="$tag"
    log "Step 6b: docker retag → ${ECR_IMAGE}:${tag} and push"
    docker tag "$REMOTE_IMAGE_LATEST" "${ECR_IMAGE}:${tag}"
    docker push "${ECR_IMAGE}:${tag}" 2>&1 | tail -3
    log_ok "Pushed to ECR: ${ECR_IMAGE}:${tag}"
  else
    log "Step 6: Push to Harbor → $REMOTE_IMAGE"
    ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" \
      "sudo docker push '$REMOTE_IMAGE' 2>&1 | tail -3"
    log_ok "Pushed: $REMOTE_IMAGE"

    log "        Push latest tag → $REMOTE_IMAGE_LATEST"
    ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" \
      "sudo docker push '$REMOTE_IMAGE_LATEST' 2>&1 | tail -3"
    log_ok "Pushed: $REMOTE_IMAGE_LATEST"

    ssh -o IdentitiesOnly=yes -i "$SSH_KEY" "$BUILD_HOST" \
      "rm -rf $REMOTE_BUILD_DIR"
  fi
}

# ----------------------------------------------------------------------
# Step 3 — Helm deploy to k3s
# ----------------------------------------------------------------------
deploy_k3s() {
  local helm_values_override="$(mktemp)"

  # Always override tag (whether build-only or deploy-only)
  cat > "$helm_values_override" <<EOF
image:
  repository: ${NEXUS_HOST}/${NEXUS_REPO}
  tag: "${IMAGE_TAG}"
  pullPolicy: Always
imagePullSecrets: []
EOF

  log "Step 7: Ensure namespace '$K3S_NAMESPACE' exists"
  kubectl --kubeconfig="$KUBECONFIG_PATH" get ns "$K3S_NAMESPACE" >/dev/null 2>&1 || \
    kubectl --kubeconfig="$KUBECONFIG_PATH" create namespace "$K3S_NAMESPACE"
  log_ok "Namespace: $K3S_NAMESPACE"

  log "Step 8: Helm upgrade --install → $RELEASE_NAME -n $K3S_NAMESPACE"
  KUBECONFIG="$KUBECONFIG_PATH" helm upgrade --install "$RELEASE_NAME" "$HELM_CHART_DIR" \
    -f "${HELM_CHART_DIR}/values.yaml" \
    -f "$HELM_VALUES_PATH" \
    -f "$helm_values_override" \
    -n "$K3S_NAMESPACE" \
    --wait --timeout 180s 2>&1 | tail -25
  rm -f "$helm_values_override"
  log_ok "Helm release applied"

  log "Step 9: Wait for rollout"
  kubectl --kubeconfig="$KUBECONFIG_PATH" rollout status "deployment/${RELEASE_NAME}" -n "$K3S_NAMESPACE" --timeout=120s 2>&1 || true
  log_ok "Deployment ready"

  log "Step 10: Verify pod status"
  kubectl --kubeconfig="$KUBECONFIG_PATH" get pods -n "$K3S_NAMESPACE" -l app="$RELEASE_NAME" -o wide
  echo

  # Quick health check via port-forward (since NodePort may not be externally reachable)
  log "Step 11: Health check (kubectl port-forward → localhost:8081)"
  ( kubectl --kubeconfig="$KUBECONFIG_PATH" port-forward "svc/$RELEASE_NAME" 8081:8080 >/dev/null 2>&1 & )
  sleep 3
  if curl -sf --max-time 10 http://localhost:8081/health >/dev/null 2>&1; then
    log_ok "/health → 200 OK"
    curl -sf --max-time 5 http://localhost:8081/health
    echo
  else
    log_warn "/health not reachable yet"
    log "  Pod events:"
    kubectl --kubeconfig="$KUBECONFIG_PATH" describe pod -n "$K3S_NAMESPACE" -l app="$RELEASE_NAME" 2>&1 | grep -A1 "Events:" | tail -15
  fi
  kill %1 2>/dev/null || true
  wait 2>/dev/null || true
}

# ----------------------------------------------------------------------
# Run
# ----------------------------------------------------------------------
case "$MODE" in
  all)
    setup_k3s_tunnel
    [ "$USE_ECR" = "false" ] && ensure_k3s_registries
    build_jar
    push_image
    deploy_k3s
    ;;
  build-only)
    build_jar
    push_image
    ;;
  deploy-only)
    setup_k3s_tunnel
    [ "$USE_ECR" = "false" ] && ensure_k3s_registries
    deploy_k3s
    ;;
esac

echo
log_ok "Done. Image: ${REMOTE_IMAGE}"
log_ok "Latest:    ${REMOTE_IMAGE_LATEST}"
echo
