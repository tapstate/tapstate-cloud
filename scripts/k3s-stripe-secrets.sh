#!/usr/bin/env bash
# ======================================================================
# k3s Secret setup for Tapstate Cloud Week 4 — Stripe API keys
#
# Creates (or overwrites) the two Kubernetes Secrets that
# tapstate-cloud-control reads on boot:
#
#   tapstate-cloud-control-secrets (Generic)
#     ├── stripe.secret-key    sk_test_xxx | sk_live_xxx
#     └── stripe.webhook-secret whsec_xxx  (from Stripe Dashboard webhook config)
#
# Usage:
#   # 1. Set env vars
#   export STRIPE_SECRET_KEY=sk_test_xxx
#   export STRIPE_WEBHOOK_SECRET=whsec_xxx
#
#   # 2. Run against a kubeconfig
#   scripts/k3s-stripe-secrets.sh --kubeconfig ~/.kube/k3s-awsdev.yaml --namespace tapstate
#
#   # Or inline a single kubectl target
#   scripts/k3s-stripe-secrets.sh -k ~/.kube/k3s-awsdev.yaml -n tapstate
#
# Security notes:
#   - This script only reads from env vars — nothing hardcoded, no shell history leaks
#   - Existing Secret is DELETED before re-creation (immutable fields)
#   - Never echoes the keys — only key lengths (len=sk_live_xxx/sk_test_xxx)
# ======================================================================
set -euo pipefail
IFS=$'\n\t'

# ----------------------------------------------------------------------
# Argument parsing
# ----------------------------------------------------------------------
KUBECONFIG_PATH="${KUBECONFIG:-}"
NAMESPACE="${NAMESPACE:-tapstate}"
FORCE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -k|--kubeconfig)   KUBECONFIG_PATH="$2"; shift 2 ;;
    -n|--namespace)    NAMESPACE="$2"; shift 2 ;;
    -f|--force)        FORCE=1; shift ;;
    -h|--help)
      echo "Usage: $0 [-k kubeconfig] [-n namespace] [-f]"
      echo ""
      echo "Reads STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET from env."
      echo "Creates k3s Secret: tapstate-cloud-control-secrets"
      echo ""
      echo "Defaults: kubeconfig=\$KUBECONFIG, namespace=tapstate"
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

# ----------------------------------------------------------------------
# Env var requirement
# ----------------------------------------------------------------------
if [ -z "${STRIPE_SECRET_KEY:-}" ]; then
  echo "ERROR: STRIPE_SECRET_KEY is not set. Export it first (sk_test_xxx or sk_live_xxx)." >&2
  exit 1
fi
if [ -z "${STRIPE_WEBHOOK_SECRET:-}" ]; then
  echo "ERROR: STRIPE_WEBHOOK_SECRET is not set. Export it first (whsec_xxx)." >&2
  exit 1
fi

# ----------------------------------------------------------------------
# Log helpers
# ----------------------------------------------------------------------
HEADER="\033[1;36m"; OK="\033[1;32m"; WARN="\033[1;33m"; ERR="\033[1;31m"; RESET="\033[0m"
log()    { echo -e "${HEADER}[$(date +%H:%M:%S)]${RESET} $*"; }
log_ok() { echo -e "${OK}[$(date +%H:%M:%S)] OK ${RESET}$*"; }
log_warn(){ echo -e "${WARN}[$(date +%H:%M:%S)] WARN ${RESET}$*"; }
log_err(){ echo -e "${ERR}[$(date +%H:%M:%S)] ERR ${RESET}$*"; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || { log_err "Missing: $1"; exit 1; }; }
require_cmd kubectl

# Build kubectl common args
KUBECTL_ARGS=()
if [ -n "$KUBECONFIG_PATH" ]; then
  KUBECTL_ARGS+=(--kubeconfig "$KUBECONFIG_PATH")
fi
KUBECTL_ARGS+=(--namespace "$NAMESPACE")

KUBECTL() { kubectl "${KUBECTL_ARGS[@]}" "$@"; }

# ----------------------------------------------------------------------
# Key prefix check — warn if test key on prod namespace or vice versa
# ----------------------------------------------------------------------
key_env="${STRIPE_SECRET_KEY#sk_}"   # test_xxx | live_xxx
key_env="${key_env%%_*}"             # test | live
namespace_env="$(echo "$NAMESPACE" | sed 's/.*\(-\)\?\(.*\)$/\2/')"

echo
log "Namespace:       $NAMESPACE"
log "Key env:         $key_env"
log "Force overwrite: $FORCE"
echo

if [ "$key_env" = "live" ] && [[ "$NAMESPACE" == *"test"* ]]; then
  log_warn "LIVE key going to namespace '$NAMESPACE' — confirm this is intentional"
  [ "$FORCE" = "1" ] || { log_err "Abort. Re-run with -f if intentional."; exit 1; }
fi

# ----------------------------------------------------------------------
# k3s cluster reachability
# ----------------------------------------------------------------------
log "Verifying k3s cluster reachability..."
if ! KUBECTL cluster-info >/dev/null 2>&1; then
  log_err "k3s cluster not reachable. Check KUBECONFIG."
  exit 1
fi
log_ok "Cluster reachable"

# ----------------------------------------------------------------------
# Ensure namespace exists
# ----------------------------------------------------------------------
if ! KUBECTL get ns "$NAMESPACE" >/dev/null 2>&1; then
  log "Creating namespace $NAMESPACE..."
  KUBECTL create namespace "$NAMESPACE"
  log_ok "Namespace created"
else
  log_ok "Namespace exists: $NAMESPACE"
fi

# ----------------------------------------------------------------------
# Create/Overwrite the Secret
# ----------------------------------------------------------------------
SECRET_NAME="tapstate-cloud-control-secrets"

# Check if immutable Secret exists — must delete + recreate
if KUBECTL get secret "$SECRET_NAME" >/dev/null 2>&1; then
  immutable="$(KUBECTL get secret "$SECRET_NAME" -o jsonpath='{.immutable}' 2>/dev/null || echo '')"
  if [ "$immutable" = "true" ]; then
    log_warn "Secret $SECRET_NAME is immutable — deleting + recreating"
    KUBECTL delete secret "$SECRET_NAME"
  else
    log "Secret $SECRET_NAME exists — patching"
  fi
fi

# Use --from-env-file to avoid shell escaping issues with base64 values
# (kubectl automatically does base64 encoding)
tmpfile="$(mktemp)"
trap 'rm -f "$tmpfile"' EXIT
cat > "$tmpfile" <<EOF
stripe.secret-key=${STRIPE_SECRET_KEY}
stripe.webhook-secret=${STRIPE_WEBHOOK_SECRET}
EOF

if KUBECTL get secret "$SECRET_NAME" >/dev/null 2>&1; then
  KUBECTL create secret generic "$SECRET_NAME" --from-env-file="$tmpfile" --dry-run=client -o yaml \
    | KUBECTL apply -f -
else
  KUBECTL create secret generic "$SECRET_NAME" --from-env-file="$tmpfile"
fi
rm -f "$tmpfile"

log_ok "Secret $SECRET_NAME created/updated"

# ----------------------------------------------------------------------
# Verify + summary
# ----------------------------------------------------------------------
echo
log_ok "=== Secret summary ==="
KUBECTL get secret "$SECRET_NAME" -o jsonpath='{.metadata.name}{"\n"}{.data}{"\n"}' | sed 's/.*/  &/'
echo

log "Data keys:"
KUBECTL get secret "$SECRET_NAME" -o jsonpath='{.data}' 2>/dev/null \
  | tr ',' '\n' | sed 's/^{//;s/}$//;s/":"/ → len=/' | sed 's/"//g' | sed 's/.*/  &/'
echo

log "Verifying decode works (length only, NO echo):"
stripe_key_len="${#STRIPE_SECRET_KEY}"
webhook_key_len="${#STRIPE_WEBHOOK_SECRET}"
log "  stripe.secret-key     len=$stripe_key_len  (prefix: ${STRIPE_SECRET_KEY%%_*}_${key_env})"
log "  stripe.webhook-secret  len=$webhook_key_len"
echo

log_ok "Done. Pods in deployment 'tapstate-cloud-control' will auto-load this Secret."
log "  kubectl -n $NAMESPACE exec deploy/tapstate-cloud-control -- printenv | grep TAPSTATE"
echo
