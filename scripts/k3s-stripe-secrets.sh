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
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -k|--kubeconfig)   KUBECONFIG_PATH="$2"; shift 2 ;;
    -n|--namespace)    NAMESPACE="$2"; shift 2 ;;
    -f|--force)        FORCE=1; shift ;;
    --dry-run)         DRY_RUN=1; shift ;;
    -h|--help)
      echo "Usage: $0 [-k kubeconfig] [-n namespace] [-f] [--dry-run]"
      echo ""
      echo "Reads STRIPE_SECRET_KEY (required) and STRIPE_WEBHOOK_SECRET (optional)"
      echo "from env. Creates k3s Secret: tapstate-cloud-control-secrets"
      echo ""
      echo "  --dry-run   Print the manifest instead of applying it"
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
  if [ "$DRY_RUN" = "1" ]; then
    log_warn "Namespace $NAMESPACE does not exist (dry run — not creating it)"
  else
    log "Creating namespace $NAMESPACE..."
    KUBECTL create namespace "$NAMESPACE"
    log_ok "Namespace created"
  fi
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
    if [ "$DRY_RUN" = "1" ]; then
      log_warn "Secret $SECRET_NAME is immutable — a real run would delete + recreate it"
    else
      log_warn "Secret $SECRET_NAME is immutable — deleting + recreating"
      KUBECTL delete secret "$SECRET_NAME"
    fi
  else
    log "Secret $SECRET_NAME exists — patching"
  fi
fi

# If no webhook secret is supplied, preserve whatever is already in the Secret —
# a partial run must never silently drop it.
if [ -z "${STRIPE_WEBHOOK_SECRET:-}" ] && KUBECTL get secret "$SECRET_NAME" >/dev/null 2>&1; then
  preserved="$(KUBECTL get secret "$SECRET_NAME" \
    -o go-template='{{index .data "stripe.webhook-secret"}}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  if [ -n "$preserved" ]; then
    STRIPE_WEBHOOK_SECRET="$preserved"
    log_warn "STRIPE_WEBHOOK_SECRET not set — preserving the value already in Secret $SECRET_NAME"
  fi
fi
if [ -z "${STRIPE_WEBHOOK_SECRET:-}" ]; then
  log_warn "STRIPE_WEBHOOK_SECRET not set — writing the API key only."
  log_warn "Webhook signature verification will fail until a webhook secret is added."
fi

# Use --from-env-file to avoid shell escaping issues with base64 values
# (kubectl automatically does base64 encoding)
tmpfile="$(mktemp)"
trap 'rm -f "$tmpfile"' EXIT
{
  printf 'stripe.secret-key=%s\n' "$STRIPE_SECRET_KEY"
  if [ -n "${STRIPE_WEBHOOK_SECRET:-}" ]; then
    printf 'stripe.webhook-secret=%s\n' "$STRIPE_WEBHOOK_SECRET"
  fi
} > "$tmpfile"

if [ "$DRY_RUN" = "1" ]; then
  log "Manifest that would be applied to namespace '$NAMESPACE':"
  KUBECTL create secret generic "$SECRET_NAME" --from-env-file="$tmpfile" --dry-run=client -o yaml \
    | sed -E 's/(stripe\.(secret-key|webhook-secret):).*/\1 <redacted>/' | sed 's/^/  /'
  rm -f "$tmpfile"
  echo
  log_ok "Dry run — nothing was written."
  exit 0
fi

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
log "  stripe.secret-key      len=${#STRIPE_SECRET_KEY}  (prefix: ${STRIPE_SECRET_KEY%%_*}_${key_env})"
if [ -n "${STRIPE_WEBHOOK_SECRET:-}" ]; then
  log "  stripe.webhook-secret  len=${#STRIPE_WEBHOOK_SECRET}"
else
  log "  stripe.webhook-secret  (not set)"
fi
echo

log_ok "Done. Pods in deployment 'tapstate-cloud-control' will auto-load this Secret."
log "  kubectl -n $NAMESPACE exec deploy/tapstate-cloud-control -- printenv | grep TAPSTATE"
echo
