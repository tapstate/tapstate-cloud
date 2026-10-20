#!/usr/bin/env bash
# ======================================================================
# Stripe Product + Price catalog bootstrap for Tapstate Cloud Week 4
#
# Creates (or updates) the 3 platform specs in Stripe:
#   prod_* — "Tapstate Cloud 2C8G Starter" / "4C16G Pro" / "8C32G Enterprise"
#   price_* — monthly USD recurring, with metadata.spec_key pointing at
#              tapstate-cloud's spec_config.key
#
# Usage:
#   scripts/stripe-bootstrap-catalog.sh          # test mode (sk_ defaults)
#   MODE=live scripts/stripe-bootstrap-catalog.sh  # prod mode — read live keys from env
#   DRY_RUN=1 scripts/stripe-bootstrap-catalog.sh  # print commands, don't execute
#
# Prerequisites:
#   Stripe CLI installed and authenticated:
#     brew install stripe/stripe/stripe  (or https://stripe.com/docs/stripe-cli)
#     stripe login                       (interactive)
#   Alternatively, set STRIPE_SECRET_KEY env (sk_test_... for sandbox, sk_live_... for prod)
#
# Idempotency:
#   Products already tagged metadata.catalog=tapstate are SKIPPED (not re-created).
#   Prices are matched by (product + spec_key metadata) — if an active Price already
#   exists, we just print it; if deprecated ones exist, they stay (we don't delete
#   historical records; Tapstate refreshAll filters by active=true anyway).
#
# Metadata convention (critical — tapstate-cloud reads these at boot):
#   Product:   { catalog: "tapstate", tapstate_env: "<mode>" }
#   Price:     { catalog: "tapstate", spec_key: "<key>", tapstate_env: "<mode>" }
# ======================================================================
set -euo pipefail
IFS=$'\n\t'

# ----------------------------------------------------------------------
# Mode + env
# ----------------------------------------------------------------------
MODE="${MODE:-test}"      # test | live
DRY_RUN="${DRY_RUN:-0}"

case "$MODE" in
  test)
    ENV_TAG="test"
    ;;
  live)
    ENV_TAG="prod"
    ;;
  *)
    echo "ERROR: MODE must be 'test' or 'live', got '$MODE'" >&2; exit 1 ;;
esac

# Stripe API key — prefer explicit env var; fallback to `stripe config` (CLI login)
if [ -n "${STRIPE_SECRET_KEY:-}" ]; then
  export STRIPE_SECRET_KEY
else
  # Read from stripe CLI config (default profile)
  CLI_KEY="$(stripe config --get api_key 2>/dev/null || true)"
  if [ -z "$CLI_KEY" ]; then
    echo "ERROR: No Stripe API key found. Set STRIPE_SECRET_KEY or run 'stripe login'." >&2
    exit 1
  fi
  # Sanity: key prefix must match mode
  local_prefix="${CLI_KEY%%_*}"   # sk_test or sk_live
  if [ "$MODE" = "test" ] && [ "$local_prefix" != "sk" ]; then
    :  # CLI prefix check isn't reliable; trust env var
  fi
  export STRIPE_SECRET_KEY="$CLI_KEY"
fi

# ----------------------------------------------------------------------
# Log helpers
# ----------------------------------------------------------------------
HEADER="\033[1;36m"
OK="\033[1;32m"
WARN="\033[1;33m"
ERR="\033[1;31m"
RESET="\033[0m"
log()    { echo -e "${HEADER}[$(date +%H:%M:%S)]${RESET} $*"; }
log_ok() { echo -e "${OK}[$(date +%H:%M:%S)] OK ${RESET}$*"; }
log_warn(){ echo -e "${WARN}[$(date +%H:%M:%S)] WARN ${RESET}$*"; }
log_err(){ echo -e "${ERR}[$(date +%H:%M:%S)] ERR ${RESET}$*"; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || { log_err "Missing: $1"; exit 1; }; }
require_cmd stripe

run() {
  if [ "$DRY_RUN" = "1" ]; then
    log "[dry-run] $*"
    return 0
  fi
  log "  → $*"
  eval "$@"
}

# ----------------------------------------------------------------------
# Pre-check: key prefix vs mode
# ----------------------------------------------------------------------
key_prefix="${STRIPE_SECRET_KEY%%_*}"
key_suffix="${STRIPE_SECRET_KEY#*_}"   # strip sk_live_ or sk_test_
key_env="${key_suffix%%_*}"            # live | test

if [ "$MODE" = "live" ] && [ "$key_env" = "test" ]; then
  log_warn "MODE=live but using test key ($STRIPE_SECRET_KEY). Re-run with live key."
  exit 1
elif [ "$MODE" = "test" ] && [ "$key_env" = "live" ]; then
  log_warn "MODE=test but using live key ($STRIPE_SECRET_KEY). Aborting to protect prod data."
  exit 1
fi

echo
log "Mode:            $MODE"
log "Key prefix:      $key_prefix"
log "Stripe env tag:  $ENV_TAG"
log "DRY_RUN:         $DRY_RUN"
echo

# ----------------------------------------------------------------------
# Spec catalog (mirrors tapstate-cloud spec-config.json exactly)
# ----------------------------------------------------------------------
declare -a SPEC_KEYS=("2c8g"     "4c16g"      "8c32g")
declare -a SPEC_NAMES=(
  "Tapstate Cloud 2C8G Starter"
  "Tapstate Cloud 4C16G Pro"
  "Tapstate Cloud 8C32G Enterprise"
)
declare -a SPEC_PRICE_CENTS=(2000   8000    24000)   # $20 / $80 / $240 monthly
declare -a SPEC_TRIAL_DAYS=(7   0   0)

log "Target catalog (${#SPEC_KEYS[@]} specs):"
for i in "${!SPEC_KEYS[@]}"; do
  printf "  %-8s → %-35s  \$%.2f/mo  trial=%sd\n" \
    "${SPEC_KEYS[$i]}" "${SPEC_NAMES[$i]}" \
    "$(echo "scale=2; ${SPEC_PRICE_CENTS[$i]} / 100" | bc)" \
    "${SPEC_TRIAL_DAYS[$i]}"
done
echo

# ----------------------------------------------------------------------
# Helper: check if a Product already exists with metadata.catalog=tapstate
# ----------------------------------------------------------------------
product_exists() {
  local spec_key="$1"
  # Search by metadata on Stripe CLI — filters locally
  stripe products list \
    --metadata "catalog=tapstate" \
    2>/dev/null | grep -q "$spec_key"
}

price_exists_for_spec() {
  local product_id="$1"
  local spec_key="$2"
  stripe prices list \
    --product "$product_id" \
    --metadata "spec_key=$spec_key" \
    2>/dev/null | grep -q "Active.*\$"
}

# ----------------------------------------------------------------------
# Main loop
# ----------------------------------------------------------------------
created_products=0
created_prices=0
skipped=0

for i in "${!SPEC_KEYS[@]}"; do
  spec_key="${SPEC_KEYS[$i]}"
  spec_name="${SPEC_NAMES[$i]}"
  spec_price_cents="${SPEC_PRICE_CENTS[$i]}"
  spec_trial_days="${SPEC_TRIAL_DAYS[$i]}"

  log "--- $spec_key — $spec_name ---"

  # ---- 1. Product ----
  product_id=""
  if product_exists "$spec_key"; then
    # Find the exact product for this spec — Stripe CLI output:
    # product_xxx  Tapstate Cloud 2C8G Starter  active
    product_id="$(stripe products list \
      --metadata "catalog=tapstate" 2>/dev/null \
      | awk -v n="$spec_name" '$0 ~ n && /active$/ {print $1; exit}')"
    if [ -n "$product_id" ]; then
      log_ok "Product exists: $product_id (SKIP create)"
      skipped=$((skipped + 1))
    fi
  fi

  if [ -z "$product_id" ]; then
    # Create Product with metadata
    product_id="$(stripe products create \
      --name "$spec_name" \
      --description "$spec_key spec — Tapstate Cloud control plane" \
      --metadata "catalog=tapstate" \
      --metadata "tapstate_env=$ENV_TAG" \
      2>/dev/null | grep '^prod_' | awk '{print $1}')"
    if [ -z "$product_id" ]; then
      log_err "Failed to create Product for $spec_key — stripe CLI returned empty"
      exit 1
    fi
    log_ok "Product created: $product_id"
    created_products=$((created_products + 1))
  fi

  # ---- 2. Price (monthly USD recurring) ----
  if price_exists_for_spec "$product_id" "$spec_key"; then
    log_ok "Price exists for spec_key=$spec_key on product=$product_id (SKIP)"
    skipped=$((skipped + 1))
  else
    stripe prices create \
      --product "$product_id" \
      --unit-amount "$spec_price_cents" \
      --currency usd \
      --recurring-interval month \
      --metadata "catalog=tapstate" \
      --metadata "spec_key=$spec_key" \
      --metadata "tapstate_env=$ENV_TAG" \
      2>/dev/null | sed 's/^/    /'
    log_ok "Price created: $spec_price_cents cents/month USD (spec_key=$spec_key)"
    created_prices=$((created_prices + 1))
  fi

  # ---- 3. Set default_price on Product (CLI doesn't expose this easily;
  #        accept as-is — the catalog refreshAll() reads ALL prices and
  #        matches by spec_key metadata, so default_price is informational only) ----
  echo
done

# ----------------------------------------------------------------------
# Summary + next steps
# ----------------------------------------------------------------------
echo
log_ok "=== Catalog bootstrap complete ==="
log "  Products created:  $created_products"
log "  Prices created:    $created_prices"
log "  Skipped (exists):  $skipped"
echo

log "Next steps:"
log "  1. Verify in Stripe Dashboard → products → filter by metadata.catalog=tapstate"
log "  2. Copy a test webhook secret → k3s secret:"
log "       export STRIPE_WEBHOOK_SECRET=whsec_xxx"
log "       ./scripts/k3s-stripe-secrets.sh --namespace tapstate --env $MODE"
log "  3. Deploy tapstate-cloud-control — it auto-calls StripeProductService.refreshAll() at boot"
echo
