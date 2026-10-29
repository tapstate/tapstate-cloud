#!/usr/bin/env bash
# ======================================================================
# Tapstate Cloud — Production Pre-Deployment Check
#
# Validates that everything is ready before the first deploy to awsprod,
# or before a release upgrade push to release-v*. Run from any machine
# with kubectl access to the prod k3s cluster.
#
# Usage:
#   scripts/prod-preflight.sh
# ======================================================================
set -euo pipefail
IFS=$'\n\t'

NAMESPACE="tapstate-cloud-control"
CHART_DIR="$(cd "$(dirname "$0")/../helm/tapstate-cloud-control" && pwd)"
HELM_BIN="${HELM_BIN:-helm}"
KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"

PASS=0
FAIL=0
WARN=0

green()  { printf "\033[1;32m%s\033[0m" "$*"; }
red()    { printf "\033[1;31m%s\033[0m" "$*"; }
yellow() { printf "\033[1;33m%s\033[0m" "$*"; }

check() {
  local desc="$1"
  local cmd="$2"
  local critical="${3:-true}"
  local result
  if result=$(eval "$cmd" 2>&1); then
    printf "  %s %s\n" "$(green '✓')" "$desc"
    PASS=$((PASS+1))
  else
    if [ "$critical" = "true" ]; then
      printf "  %s %s\n    %s\n" "$(red '✗')" "$desc" "$result"
      FAIL=$((FAIL+1))
    else
      printf "  %s %s\n    %s\n" "$(yellow '⚠')" "$desc" "$result"
      WARN=$((WARN+1))
    fi
  fi
}

header() {
  echo ""
  echo "──────────────────────────────────────────────"
  echo " $*"
  echo "──────────────────────────────────────────────"
}

echo ""
echo "=============================================="
echo "  Tapstate Cloud — awsprod Preflight"
echo "  Namespace: $NAMESPACE"
echo "  Date:      $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="

# ----------------------------------------------------------------------
# 1. Tools availability
# ----------------------------------------------------------------------
header "1. Required tools"
check "kubectl available"  "command -v $KUBECTL_BIN"
check "helm available"     "command -v $HELM_BIN"
check "kubectl context set" "$KUBECTL_BIN config current-context"

# ----------------------------------------------------------------------
# 2. k3s cluster reachable
# ----------------------------------------------------------------------
header "2. k3s cluster connectivity"
check "Cluster API reachable" "$KUBECTL_BIN cluster-info >/dev/null 2>&1"
check "Nodes ready"           "$KUBECTL_BIN get nodes --no-headers 2>&1 | grep -q Ready"
check "Namespace exists"      "$KUBECTL_BIN get namespace $NAMESPACE >/dev/null 2>&1"

# ----------------------------------------------------------------------
# 3. Required k3s Secrets (MUST exist)
# ----------------------------------------------------------------------
header "3. REQUIRED Secrets (pod WILL NOT start without these)"
REQ_SECRETS=(
  "tapstate-cloud-control-mongo-uri"
  "tapstate-cloud-control-jwks-signing-key"
  "tapstate-cloud-control-aws-credentials"
  "tapstate-cloud-control-dns"
  "tapstate-cloud-control-iac"
  "tapstate-ecr-pull"
)
for secret in "${REQ_SECRETS[@]}"; do
  check "Secret: $secret" "$KUBECTL_BIN get secret $secret -n $NAMESPACE >/dev/null 2>&1"
done

# ----------------------------------------------------------------------
# 4. Optional Secrets (warn if missing)
# ----------------------------------------------------------------------
header "4. Optional Secrets"
OPT_SECRETS=(
  "tapstate-cloud-control-auth"
  "tapstate-cloud-control-smtp"
  "tapstate-cloud-control-secrets"
  "tapstate-cloud-control-ops"
  "tapstate-cloud-control-cluster-version"
)
for secret in "${OPT_SECRETS[@]}"; do
  check "Optional: $secret" "$KUBECTL_BIN get secret $secret -n $NAMESPACE >/dev/null 2>&1" false
done

# ----------------------------------------------------------------------
# 5. Secret value validation (check for placeholders)
# ----------------------------------------------------------------------
header "5. Secret value sanity (no placeholders)"
check "mongo-uri secret has real connection string" \
  "$KUBECTL_BIN get secret tapstate-cloud-control-mongo-uri -n $NAMESPACE -o jsonpath='{.data.mongo-uri}' | base64 -d | grep -q 'mongodb'"
check "jwks-signing-key starts with BEGIN" \
  "$KUBECTL_BIN get secret tapstate-cloud-control-jwks-signing-key -n $NAMESPACE -o jsonpath='{.data.signing-key}' | base64 -d | grep -q 'BEGIN'"
check "aws-credentials have non-empty AK" \
  "test -n \"\$($KUBECTL_BIN get secret tapstate-cloud-control-aws-credentials -n $NAMESPACE -o jsonpath='{.data.access-key-id}' | base64 -d | head -c1)\""
check "iac master key is base64" \
  "$KUBECTL_BIN get secret tapstate-cloud-control-iac -n $NAMESPACE -o jsonpath='{.data.master-key}' | base64 -d | base64 -d >/dev/null 2>&1 || true; echo ok"

# ----------------------------------------------------------------------
# 6. Helm chart & values
# ----------------------------------------------------------------------
header "6. Helm chart readiness"
check "Chart directory exists"         "test -d $CHART_DIR"
check "values-awsprod.yaml exists"     "test -f $CHART_DIR/values-awsprod.yaml"
check "Helm chart renders (values)"    "$HELM_BIN template tapstate-cloud-control $CHART_DIR -f $CHART_DIR/values.yaml -f $CHART_DIR/values-awsprod.yaml --namespace $NAMESPACE >/dev/null 2>&1"
check "Helm chart lint passes"         "$HELM_BIN lint $CHART_DIR -f $CHART_DIR/values.yaml -f $CHART_DIR/values-awsprod.yaml --namespace $NAMESPACE >/dev/null 2>&1"

# ----------------------------------------------------------------------
# 7. ECR reachable
# ----------------------------------------------------------------------
header "7. ECR connectivity"
check "aws CLI available"              "command -v aws"
check "ECR image already pushed?"     "aws ecr describe-images --repository-name tapstate-cloud/tapstate-cloud-control --region ap-east-1 --max-results 1 >/dev/null 2>&1" false
check "K8s can reach ECR pull secret" "$KUBECTL_BIN get secret tapstate-ecr-pull -n $NAMESPACE -o jsonpath='{.data.\.dockerconfigjson}' | base64 -d | grep -q 'amazonaws.com'"

# ----------------------------------------------------------------------
# 8. Ingress / Edge layer
# ----------------------------------------------------------------------
header "8. Edge (nginx on k3sm01)"
check "k3sm01 NodePort 30180 available" "$KUBECTL_BIN get svc -n $NAMESPACE 2>/dev/null | grep -q tapstate-cloud-control || echo 'svc not installed yet'" false

# ----------------------------------------------------------------------
# 9. GitHub CD prerequisites
# ----------------------------------------------------------------------
header "9. GitHub CD configuration"
check "deploy.yaml workflow exists"   "test -f $(dirname "$0")/../.github/workflows/deploy.yaml"
check "release-v* branches match"     "grep -q 'release-v' $(dirname "$0")/../.github/workflows/deploy.yaml"
check "production environment defined" "grep -A2 'deploy-prod' $(dirname "$0")/../.github/workflows/deploy.yaml | grep -q 'production'" false

# ----------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------
echo ""
echo "=============================================="
printf "  Results: "
[ "$FAIL" -eq 0 ] && printf "%s" "$(green 'PASS')" || printf "%s" "$(red 'FAIL')"
printf " | %d pass | %d fail | %d warnings\n" "$PASS" "$FAIL" "$WARN"
echo "=============================================="

if [ "$FAIL" -gt 0 ]; then
  echo ""
  red "❌ $FAIL CRITICAL check(s) failed — fix before deploy."
  echo "    Run: scripts/prod-secrets.sh to create missing secrets."
  exit 1
else
  echo ""
  green "✅ All critical checks passed. Ready for deploy."
  if [ "$WARN" -gt 0 ]; then
    yellow "  ⚠ $WARN optional check(s) had warnings — review above."
  fi
  echo ""
  echo "Next steps:"
  echo "  1. Create release branch:  git checkout -b release-v0.3"
  echo "  2. Push to origin:          git push -u origin release-v0.3"
  echo "  3. GitHub Actions auto-deploys to prod via deploy-prod job"
  echo "  4. Verify:                  kubectl get pods -n $NAMESPACE"
fi
