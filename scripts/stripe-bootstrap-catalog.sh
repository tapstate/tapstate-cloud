#!/usr/bin/env bash
# ======================================================================
# Stripe Product + Price catalog bootstrap for Tapstate Cloud Week 4
#
# Creates 3 platform specs via Stripe REST API (no CLI dependency — curl + jq).
# Products and Prices are idempotently looked up by metadata before create.
#
# Price changes:
#   Stripe Prices are immutable. When the target amount differs from the active
#   Price, a NEW Price is created and the old one is archived (active=false) so
#   the backend catalog still sees exactly one active Price per spec_key.
#
# Current pricing: 2c8g=$199/mo, 4c16g=$399/mo, 8c32g=$799/mo (USD, monthly)
#
# Usage:
#   export STRIPE_SECRET_KEY=sk_test_xxx
#   ./scripts/stripe-bootstrap-catalog.sh
#   MODE=live ./scripts/stripe-bootstrap-catalog.sh   # aborts if key is test
#   DRY_RUN=1 ./scripts/stripe-bootstrap-catalog.sh  # prints curl commands
#
# Metadata convention (tapstate-cloud reads these at boot):
#   Product:  { catalog: "tapstate", tapstate_env: "<mode>", spec_key: "<key>" }
#   Price:    { catalog: "tapstate", tapstate_env: "<mode>", spec_key: "<key>" }
# ======================================================================
set -euo pipefail
IFS=$'\n\t'

# ----------------------------------------------------------------------
# Mode + env
# ----------------------------------------------------------------------
MODE="${MODE:-test}"
DRY_RUN="${DRY_RUN:-0}"
STRIPE_VERSION="2024-10-28"   # Stripe API version header
# Stripe API root INCLUDES the /v1 path segment. Overridable so the rotation
# path can be exercised against a local mock.
STRIPE_API_BASE="${STRIPE_API_BASE:-https://api.stripe.com/v1}"

case "$MODE" in
  test) ENV_TAG="test" ;;
  live) ENV_TAG="prod" ;;
  *)    echo "ERROR: MODE must be 'test' or 'live'" >&2; exit 1 ;;
esac

STRIPE_SECRET_KEY="${STRIPE_SECRET_KEY:-}"
if [ -z "$STRIPE_SECRET_KEY" ]; then
  echo "ERROR: STRIPE_SECRET_KEY is not set." >&2; exit 1
fi

key_env="${STRIPE_SECRET_KEY#sk_}"; key_env="${key_env%%_*}"
if [ "$MODE" = "live" ] && [ "$key_env" = "test" ]; then
  echo "ERROR: MODE=live with a TEST key. Aborting." >&2; exit 1
fi
if [ "$MODE" = "test" ] && [ "$key_env" = "live" ]; then
  echo "ERROR: MODE=test with a LIVE key. Aborting." >&2; exit 1
fi

require_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not installed" >&2; exit 1; }; }
require_cmd curl
require_cmd jq

# ----------------------------------------------------------------------
# Log helpers
# ----------------------------------------------------------------------
HEADER="\033[1;36m"; OK="\033[1;32m"; WARN="\033[1;33m"; ERR="\033[1;31m"; RESET="\033[0m"
log()    { echo -e "${HEADER}[$(date +%H:%M:%S)]${RESET} $*" >&2; }
log_ok() { echo -e "${OK}[$(date +%H:%M:%S)] OK ${RESET}$*" >&2; }
log_warn(){ echo -e "${WARN}[$(date +%H:%M:%S)] WARN ${RESET}$*" >&2; }
log_err(){ echo -e "${ERR}[$(date +%H:%M:%S)] ERR ${RESET}$*" >&2; }

# ----------------------------------------------------------------------
# Stripe API call — uses form-encoded body
#   stripe_call METHOD PATH [data_key=value data_key2=value2 ...]
#   Returns JSON body on stdout; exits 1 on HTTP error (message to stderr)
# ----------------------------------------------------------------------
stripe_call() {
  local method="$1" path="$2"; shift 2
  local url="${STRIPE_API_BASE}${path}"
  local -a form_args=()
  for pair in "$@"; do
    form_args+=(--data-urlencode "$pair")
  done

  local http_code body extra_args=()
  if [ "$DRY_RUN" = "1" ]; then
    log "[dry-run] curl -X $method ${url}"
    for pair in "$@"; do log "           ${pair}"; done
    # Return shape-correct fake JSON so downstream jq parsing works
    if [ "$method" = "GET" ]; then
      case "$path" in
        /account*) echo '{"id":"acct_dryrun"}' ;;
        *)         echo '{"data":[],"has_more":false}' ;;
      esac
    else
      case "$path" in
        /products*) echo '{"id":"prod_dryrun","active":true}' ;;
        /prices*)   echo '{"id":"price_dryrun","active":true}' ;;
        *)          echo '{"id":"dryrun","active":true}' ;;
      esac
    fi
    return 0
  fi

  if [ ${#form_args[@]} -gt 0 ]; then
    extra_args=("${form_args[@]}")
  fi

  http_code="$(curl -sS -o /tmp/stripe_body.$$ -w '%{http_code}' \
    -X "$method" "$url" \
    -H "Stripe-Version: ${STRIPE_VERSION}" \
    -u "${STRIPE_SECRET_KEY}:" \
    "${extra_args[@]+"${extra_args[@]}"}")"
  body="$(cat /tmp/stripe_body.$$)"
  rm -f /tmp/stripe_body.$$

  if [ "$http_code" -ge 400 ]; then
    log_err "Stripe HTTP $http_code → $method $path"
    echo "$body" | jq -r '.error.message // .' 2>/dev/null || echo "$body"
    return 1
  fi
  echo "$body"
}

# ----------------------------------------------------------------------
# Spec catalog (mirrors tapstate-cloud spec-config.json exactly)
# ----------------------------------------------------------------------
declare -a SPEC_KEYS=("2c8g"   "4c16g"    "8c32g")
declare -a SPEC_NAMES=(
  "Tapstate Cloud 2C8G Starter"
  "Tapstate Cloud 4C16G Pro"
  "Tapstate Cloud 8C32G Enterprise"
)
declare -a SPEC_PRICE_CENTS=(19900  39900   79900)   # $199 / $399 / $799 monthly

echo
log "Mode: $MODE  (key=$key_env, stripe_env_tag=$ENV_TAG)"
log "API:  ${STRIPE_API_BASE}  (version=$STRIPE_VERSION)"
log "DRY:  $DRY_RUN"
echo

# ----------------------------------------------------------------------
# Step 0a: verify the API key actually works (fail fast with a clear error)
# ----------------------------------------------------------------------
log "Verifying Stripe API key..."
account_resp="$(stripe_call GET "/account")" || {
  log_err "Stripe API key rejected — check STRIPE_SECRET_KEY ($key_env mode)"
  exit 1
}
account_id="$(echo "$account_resp" | jq -r '.id // empty')"
if [ -z "$account_id" ]; then
  log_err "Stripe /account returned no id — response: $account_resp"
  exit 1
fi
log_ok "Authenticated as $account_id"

# ----------------------------------------------------------------------
# Step 0b: fetch existing tapstate products
# ----------------------------------------------------------------------
log "Fetching existing products tagged catalog=tapstate..."
products_resp="$(stripe_call GET "/products?limit=100&active=true")"
existing_map="$(echo "$products_resp" | jq -r \
  '[.data[] | select(.metadata.catalog=="tapstate") | {id, name, spec_key: (.metadata.spec_key // "")}] |
    map({(.spec_key // .name): .id}) | add // {}')"
log "  Found: $(echo "$existing_map" | jq 'keys | length') existing product(s)"

# ----------------------------------------------------------------------
# Main loop
# ----------------------------------------------------------------------
created_products=0; created_prices=0; rotated_prices=0; skipped=0

for i in "${!SPEC_KEYS[@]}"; do
  spec_key="${SPEC_KEYS[$i]}"
  spec_name="${SPEC_NAMES[$i]}"
  spec_price_cents="${SPEC_PRICE_CENTS[$i]}"

  log "--- $spec_key — $spec_name ---"

  # ---- 1. Ensure Product exists ----
  product_id="$(echo "$existing_map" | jq -r --arg sk "$spec_key" '.[$sk] // empty')"

  if [ -n "$product_id" ]; then
    log_ok "Product exists: $product_id"
  else
    log "  Creating Product '$spec_name'..."
    product_resp="$(stripe_call POST "/products" \
      "name=${spec_name}" \
      "description=${spec_key} spec — Tapstate Cloud control plane" \
      "metadata[catalog]=tapstate" \
      "metadata[tapstate_env]=${ENV_TAG}" \
      "metadata[spec_key]=${spec_key}")" || continue
    product_id="$(echo "$product_resp" | jq -r '.id')"
    log_ok "Product created: $product_id"
    created_products=$((created_products + 1))
  fi

  # ---- 2. Find existing active price for this spec_key ----
  prices_resp="$(stripe_call GET "/prices?product=${product_id}&limit=100")"
  existing_price="$(echo "$prices_resp" | jq -r --arg sk "$spec_key" \
    '.data[] | select(.active==true and .metadata.spec_key==$sk) | .id' | head -1)"

  existing_amount=""
  if [ -n "$existing_price" ]; then
    existing_amount="$(echo "$prices_resp" | jq -r --arg pid "$existing_price" \
      '.data[] | select(.id==$pid) | .unit_amount')"
  fi

  if [ -n "$existing_price" ] && [ "$existing_amount" = "$spec_price_cents" ]; then
    log_ok "Price up to date: $existing_price (\$$((existing_amount / 100))/mo, spec_key=$spec_key)"
    skipped=$((skipped + 1))
  else
    if [ -n "$existing_price" ]; then
      # Stripe Prices are immutable — an amount change requires a NEW Price.
      log "  Amount changed: \$$((existing_amount / 100)) → \$$((spec_price_cents / 100))/mo — rotating price"
    else
      log "  Creating Price: \$$((spec_price_cents / 100))/mo USD recurring monthly (spec_key=$spec_key)..."
    fi

    price_resp="$(stripe_call POST "/prices" \
      "product=${product_id}" \
      "unit_amount=${spec_price_cents}" \
      "currency=usd" \
      "recurring[interval]=month" \
      "metadata[catalog]=tapstate" \
      "metadata[spec_key]=${spec_key}" \
      "metadata[tapstate_env]=${ENV_TAG}")" || continue
    price_id="$(echo "$price_resp" | jq -r '.id')"
    log_ok "Price created: $price_id (\$$((spec_price_cents / 100))/mo, spec_key=$spec_key)"
    created_prices=$((created_prices + 1))

    # Archive the superseded Price. The backend catalog requires exactly one
    # active Price per spec_key, otherwise refreshAll() reports an integrity
    # violation and refuses to sync.
    if [ -n "$existing_price" ]; then
      stripe_call POST "/prices/${existing_price}" "active=false" >/dev/null || {
        log_err "Failed to archive old price $existing_price — backend will see two active prices for spec_key=$spec_key"
        continue
      }
      log_ok "Old price archived: $existing_price"
      rotated_prices=$((rotated_prices + 1))
    fi
  fi
  echo
done

# ----------------------------------------------------------------------
# Summary + final verification listing
# ----------------------------------------------------------------------
echo
log_ok "=== Bootstrap summary ==="
log "  Products created:  $created_products"
log "  Prices created:    $created_prices"
log "  Prices rotated:    $rotated_prices"
log "  Skipped (current): $skipped"
echo

log "Final Stripe catalog (catalog=tapstate):"
all_products="$(stripe_call GET "/products?limit=100&active=true" 2>/dev/null || echo '{"data":[]}')"
all_prices="$(stripe_call GET "/prices?limit=100&active=true&type=recurring" 2>/dev/null || echo '{"data":[]}')"

echo "$all_products" | jq -r '.data[] | select(.metadata.catalog=="tapstate") |
  "\(.id)\t\(.name)\tspec_key=\(.metadata.spec_key // "none")\tenv=\(.metadata.tapstate_env // "?")"' 2>/dev/null |
  sed 's/^/  /' | sort

echo
echo "$all_prices" | jq -r '.data[] | select(.metadata.catalog=="tapstate") |
  "\(.id)\t\(.unit_amount)\(.currency)\tinterval=\(.recurring.interval)\tspec_key=\(.metadata.spec_key // "none")"' 2>/dev/null |
  sed 's/^/  /' | sort

echo
log_ok "Done. Verify in Stripe Dashboard: https://dashboard.stripe.com/${key_env}/products"
echo
