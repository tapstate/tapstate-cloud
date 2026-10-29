#!/usr/bin/env bash
# ======================================================================
# Tapstate Cloud — Create k3s Secrets for awsprod environment
#
# This script creates ALL k3s Secrets required by the awsprod namespace
# (tapstate-cloud-control) before the first Helm install, or refreshes
# them on subsequent runs. It mirrors the awsdev secret set but targets
# the production namespace and production OAuth callback URLs.
#
# Usage:
#   scripts/prod-secrets.sh [--apply|--dry-run] [--force]
#
#   Default is --apply (create or update secrets in-place).
#   --dry-run prints the kubectl commands without executing them.
#   --force allows rotating secrets that normally get skipped if they
#           already exist (e.g. iac master key, otp HMAC key).
#
# Secrets that need real values (not generated here) are read from env
# or prompted interactively. DO NOT hardcode production credentials.
# ======================================================================
set -euo pipefail
IFS=$'\n\t'

NAMESPACE="tapstate-cloud-control"
MODE="apply"
FORCE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) MODE="dry-run"; shift ;;
    --apply)   MODE="apply"; shift ;;
    --force)   FORCE=true; shift ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

KUBECTL="kubectl --namespace $NAMESPACE"

run() {
  if [ "$MODE" = "dry-run" ]; then
    echo "  [DRY] $*"
  else
    "$@"
  fi
}

apply_or_create() {
  local secret_name="$1"
  shift
  local dry_run_flags=(--dry-run=client -o yaml)

  if [ "$MODE" = "dry-run" ]; then
    echo "  [DRY] kubectl get secret $secret_name -n $NAMESPACE"
    kubectl get secret "$secret_name" -n "$NAMESPACE" >/dev/null 2>&1 && echo "  [DRY] would update $secret_name" || echo "  [DRY] would create $secret_name"
    echo "  [DRY] kubectl create secret generic $secret_name -n $NAMESPACE --from-literal=... --dry-run=client -o yaml | kubectl apply -f -"
    return 0
  fi

  # Always use dry-run + apply for idempotent create/update
  kubectl create secret generic "$secret_name" -n "$NAMESPACE" \
    "$@" --dry-run=client -o yaml | kubectl apply -f -
  echo "  ✓ $secret_name"
}

echo "=============================================="
echo "Tapstate Cloud awsprod — Secrets Setup"
echo "Namespace: $NAMESPACE"
echo "Mode:      $MODE"
echo "=============================================="
echo

# ----------------------------------------------------------------------
# 1. Namespace (create if missing)
# ----------------------------------------------------------------------
echo "→ 1. Ensure namespace exists"
if [ "$MODE" = "dry-run" ]; then
  echo "  [DRY] kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -"
else
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
  echo "  ✓ namespace $NAMESPACE ready"
fi
echo

# ----------------------------------------------------------------------
# 2. MongoDB Atlas URI
#    Required: Control Plane metadata DB (cloud-control-user)
# ----------------------------------------------------------------------
echo "→ 2. MongoDB Atlas URI (tapstate-cloud-control-mongo-uri)"
if [ -n "${PROD_MONGO_URI:-}" ]; then
  apply_or_create tapstate-cloud-control-mongo-uri \
    --from-literal="mongo-uri=${PROD_MONGO_URI}"
else
  echo "  ⚠ PROD_MONGO_URI not set — skipping (expected format:"
  echo "    mongodb+srv://cloud-control-user:PASSWORD@tapstate-cloud-control.XXXXX.mongodb.net/tapstate_control?authSource=admin&tls=true)"
fi
echo

# ----------------------------------------------------------------------
# 3. JWKS Signing Key
#    Required: PEM-encoded RSA private key
#    Generate one-time: openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt
# ----------------------------------------------------------------------
echo "→ 3. JWKS Signing Key (tapstate-cloud-control-jwks-signing-key)"
if [ -n "${PROD_JWKS_SIGNING_KEY:-}" ]; then
  apply_or_create tapstate-cloud-control-jwks-signing-key \
    --from-literal="signing-key=${PROD_JWKS_SIGNING_KEY}"
elif [ -n "${PROD_JWKS_SIGNING_KEY_FILE:-}" ] && [ -f "$PROD_JWKS_SIGNING_KEY_FILE" ]; then
  apply_or_create tapstate-cloud-control-jwks-signing-key \
    --from-file="signing-key=${PROD_JWKS_SIGNING_KEY_FILE}"
else
  echo "  ⚠ PROD_JWKS_SIGNING_KEY or PROD_JWKS_SIGNING_KEY_FILE not set — skipping"
fi
echo

# ----------------------------------------------------------------------
# 4. AWS Credentials (for CloudFormation EC2 provisioning)
#    Required: AK/SK for the IAM role that manages per-cluster resources
# ----------------------------------------------------------------------
echo "→ 4. AWS Credentials (tapstate-cloud-control-aws-credentials)"
if [ -n "${PROD_AWS_ACCESS_KEY_ID:-}" ] && [ -n "${PROD_AWS_SECRET_ACCESS_KEY:-}" ]; then
  apply_or_create tapstate-cloud-control-aws-credentials \
    --from-literal="access-key-id=${PROD_AWS_ACCESS_KEY_ID}" \
    --from-literal="secret-access-key=${PROD_AWS_SECRET_ACCESS_KEY}"
else
  echo "  ⚠ PROD_AWS_ACCESS_KEY_ID / PROD_AWS_SECRET_ACCESS_KEY not set — skipping"
fi
echo

# ----------------------------------------------------------------------
# 5. GoDaddy DNS API Key
#    Required: prod uses iacMode=real
#    PAT token format: gd_pat_... with blank api-secret
# ----------------------------------------------------------------------
echo "→ 5. GoDaddy DNS (tapstate-cloud-control-dns)"
if [ -n "${PROD_GODADDY_API_KEY:-}" ]; then
  GODADDY_SECRET="${PROD_GODADDY_API_SECRET:-}"
  apply_or_create tapstate-cloud-control-dns \
    --from-literal="api-key=${PROD_GODADDY_API_KEY}" \
    --from-literal="api-secret=${GODADDY_SECRET}"
else
  echo "  ⚠ PROD_GODADDY_API_KEY not set — skipping"
fi
echo

# ----------------------------------------------------------------------
# 6. IaC SSH-key Encryption Master Key
#    Required: prod uses iacMode=real
#    Base64 of 32 random bytes. DO NOT rotate casually — it encrypts
#    existing per-cluster SSH keys at rest.
# ----------------------------------------------------------------------
echo "→ 6. IaC Crypto Master Key (tapstate-cloud-control-iac)"
if $FORCE; then
  IAC_MASTER_KEY="$(head -c32 /dev/urandom | base64)"
  echo "  🔄 FORCE mode: generating NEW master key (existing cluster SSH keys will NOT decrypt!)"
  apply_or_create tapstate-cloud-control-iac \
    --from-literal="master-key=${IAC_MASTER_KEY}"
elif [ -n "${PROD_IAC_MASTER_KEY:-}" ]; then
  apply_or_create tapstate-cloud-control-iac \
    --from-literal="master-key=${PROD_IAC_MASTER_KEY}"
else
  # Only generate if secret does not exist yet
  if ! kubectl get secret tapstate-cloud-control-iac -n "$NAMESPACE" >/dev/null 2>&1; then
    IAC_MASTER_KEY="$(head -c32 /dev/urandom | base64)"
    apply_or_create tapstate-cloud-control-iac \
      --from-literal="master-key=${IAC_MASTER_KEY}"
  else
    echo "  ℹ tapstate-cloud-control-iac already exists — keeping existing value (use --force to rotate)"
  fi
fi
echo

# ----------------------------------------------------------------------
# 7. ECR Pull Secret
#    Required: prod nodes have no IAM instance profile
#    Refreshed every deploy by GitHub Actions + by the CronJob
# ----------------------------------------------------------------------
echo "→ 7. ECR Pull Secret (tapstate-ecr-pull)"
ECR_HOST="263186327888.dkr.ecr.ap-east-1.amazonaws.com"
if aws ecr get-login-password --region ap-east-1 >/dev/null 2>&1; then
  ECR_TOKEN="$(aws ecr get-login-password --region ap-east-1)"
  apply_or_create tapstate-ecr-pull \
    --docker-server="${ECR_HOST}" \
    --docker-username=AWS \
    --docker-password="${ECR_TOKEN}"
else
  echo "  ⚠ Cannot get ECR login token — check AWS CLI credentials"
  echo "    Run: aws configure --profile prod-ecr"
fi
echo

# ----------------------------------------------------------------------
# 8. OTP HMAC Key (stable across restarts)
#    DO NOT rotate casually — invalidates outstanding email verification codes
# ----------------------------------------------------------------------
echo "→ 8. Auth OTP HMAC Key (tapstate-cloud-control-auth)"
if $FORCE; then
  OTP_HMAC_KEY="$(openssl rand -hex 32)"
  echo "  🔄 FORCE mode: generating NEW HMAC key"
  apply_or_create tapstate-cloud-control-auth \
    --from-literal="otp-hmac-key=${OTP_HMAC_KEY}"
elif [ -n "${PROD_OTP_HMAC_KEY:-}" ]; then
  apply_or_create tapstate-cloud-control-auth \
    --from-literal="otp-hmac-key=${PROD_OTP_HMAC_KEY}"
else
  if ! kubectl get secret tapstate-cloud-control-auth -n "$NAMESPACE" >/dev/null 2>&1; then
    OTP_HMAC_KEY="$(openssl rand -hex 32)"
    apply_or_create tapstate-cloud-control-auth \
      --from-literal="otp-hmac-key=${OTP_HMAC_KEY}"
  else
    echo "  ℹ tapstate-cloud-control-auth already exists — keeping"
  fi
fi
echo

# ----------------------------------------------------------------------
# 9. Google OAuth
#    Callback: https://cloud.tapstate.com/login/oauth2/code/google
# ----------------------------------------------------------------------
echo "→ 9. Google OAuth (tapstate-cloud-control-google-oauth)"
if [ -n "${PROD_GOOGLE_OAUTH_CLIENT_ID:-}" ]; then
  apply_or_create tapstate-cloud-control-google-oauth \
    --from-literal="client-id=${PROD_GOOGLE_OAUTH_CLIENT_ID}" \
    --from-literal="client-secret=${PROD_GOOGLE_OAUTH_CLIENT_SECRET}"
else
  echo "  ℹ Skipping (optional — set PROD_GOOGLE_OAUTH_CLIENT_ID to enable)"
fi
echo

# ----------------------------------------------------------------------
# 10. GitHub OAuth
#     Callback: https://cloud.tapstate.com/login/oauth2/code/github
# ----------------------------------------------------------------------
echo "→ 10. GitHub OAuth (tapstate-cloud-control-github-oauth)"
if [ -n "${PROD_GITHUB_OAUTH_CLIENT_ID:-}" ]; then
  apply_or_create tapstate-cloud-control-github-oauth \
    --from-literal="client-id=${PROD_GITHUB_OAUTH_CLIENT_ID}" \
    --from-literal="client-secret=${PROD_GITHUB_OAUTH_CLIENT_SECRET}"
else
  echo "  ℹ Skipping (optional — set PROD_GITHUB_OAUTH_CLIENT_ID to enable)"
fi
echo

# ----------------------------------------------------------------------
# 11. SMTP (shared by auth verification + alert email)
# ----------------------------------------------------------------------
echo "→ 11. SMTP (tapstate-cloud-control-smtp)"
if [ -n "${PROD_SMTP_HOST:-}" ] && [ -n "${PROD_SMTP_USERNAME:-}" ]; then
  apply_or_create tapstate-cloud-control-smtp \
    --from-literal="host=${PROD_SMTP_HOST}" \
    --from-literal="username=${PROD_SMTP_USERNAME}" \
    --from-literal="password=${PROD_SMTP_PASSWORD:-}"
else
  echo "  ℹ Skipping (optional — set PROD_SMTP_HOST to enable auth mail + alert email)"
fi
echo

# ----------------------------------------------------------------------
# 12. Stripe (payment — optional)
#     Created by scripts/k3s-stripe-secrets.sh; just create placeholder here
# ----------------------------------------------------------------------
echo "→ 12. Stripe (tapstate-cloud-control-secrets)"
if [ -n "${PROD_STRIPE_SECRET_KEY:-}" ]; then
  apply_or_create tapstate-cloud-control-secrets \
    --from-literal="stripe.secret-key=${PROD_STRIPE_SECRET_KEY}" \
    --from-literal="stripe.webhook-secret=${PROD_STRIPE_WEBHOOK_SECRET:-}"
else
  echo "  ℹ Skipping (optional — set PROD_STRIPE_SECRET_KEY to enable payments)"
fi
echo

# ----------------------------------------------------------------------
# 13. Ops Subscription Cancel Token (optional, operator use)
# ----------------------------------------------------------------------
echo "→ 13. Ops Cancel Token (tapstate-cloud-control-ops)"
if [ -n "${PROD_OPS_SUBSCRIPTION_TOKEN:-}" ]; then
  apply_or_create tapstate-cloud-control-ops \
    --from-literal="subscription-token=${PROD_OPS_SUBSCRIPTION_TOKEN}"
else
  if ! kubectl get secret tapstate-cloud-control-ops -n "$NAMESPACE" >/dev/null 2>&1; then
    OPS_TOKEN="$(openssl rand -hex 32)"
    apply_or_create tapstate-cloud-control-ops \
      --from-literal="subscription-token=${OPS_TOKEN}"
    echo "  📌 Save this token somewhere secure — it will not be shown again:"
    echo "     $OPS_TOKEN"
  else
    echo "  ℹ Already exists — keeping"
  fi
fi
echo

# ----------------------------------------------------------------------
# 14. Cluster Version Release Token (optional)
# ----------------------------------------------------------------------
echo "→ 14. Cluster Version Release Token (tapstate-cloud-control-cluster-version)"
if ! kubectl get secret tapstate-cloud-control-cluster-version -n "$NAMESPACE" >/dev/null 2>&1; then
  VERSION_TOKEN="$(openssl rand -hex 32)"
  apply_or_create tapstate-cloud-control-cluster-version \
    --from-literal="release-token=${VERSION_TOKEN}"
  echo "  📌 Save this token — it guards the version release/operator endpoints:"
  echo "     $VERSION_TOKEN"
else
  echo "  ℹ Already exists — keeping"
fi
echo

# ----------------------------------------------------------------------
# 15. Tawk Webhook Secret (optional)
# ----------------------------------------------------------------------
echo "→ 15. Tawk Webhook (tapstate-cloud-control-tawk-webhook)"
if [ -n "${PROD_TAWK_WEBHOOK_SECRET:-}" ]; then
  apply_or_create tapstate-cloud-control-tawk-webhook \
    --from-literal="webhook-secret=${PROD_TAWK_WEBHOOK_SECRET}"
else
  echo "  ℹ Skipping (optional)"
fi
echo

# ----------------------------------------------------------------------
# 16. Feishu Bot (optional — feedback channel)
# ----------------------------------------------------------------------
echo "→ 16. Feishu Bot (tapstate-cloud-control-feishu-bot)"
if [ -n "${PROD_FEISHU_APP_ID:-}" ]; then
  apply_or_create tapstate-cloud-control-feishu-bot \
    --from-literal="app-id=${PROD_FEISHU_APP_ID}" \
    --from-literal="app-secret=${PROD_FEISHU_APP_SECRET}" \
    --from-literal="chat-id=${PROD_FEISHU_CHAT_ID:-}"
else
  echo "  ℹ Skipping (optional)"
fi
echo

# ----------------------------------------------------------------------
# 17. Sample Sources Password (optional)
# ----------------------------------------------------------------------
echo "→ 17. Sample Sources DB Password (tapstate-cloud-control-sample-sources)"
if [ -n "${PROD_SAMPLE_SOURCES_PASSWORD:-}" ]; then
  apply_or_create tapstate-cloud-control-sample-sources \
    --from-literal="password=${PROD_SAMPLE_SOURCES_PASSWORD}"
else
  echo "  ℹ Skipping (optional — Cluster Consoles hide sample entry without it)"
fi
echo

# ----------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------
echo "=============================================="
echo "Done. Existing secrets are preserved; new ones created."
echo ""
echo "Verify all required secrets exist:"
echo "  kubectl get secrets -n $NAMESPACE"
echo ""
echo "Required before first helm install:"
echo "  ✓ tapstate-cloud-control-mongo-uri        (MUST have real value)"
echo "  ✓ tapstate-cloud-control-jwks-signing-key (MUST have real value)"
echo "  ✓ tapstate-cloud-control-aws-credentials  (MUST have real value)"
echo "  ✓ tapstate-cloud-control-dns              (required for iacMode=real)"
echo "  ✓ tapstate-cloud-control-iac              (iac crypto master key)"
echo "  ✓ tapstate-ecr-pull                       (image pull auth)"
echo "=============================================="
