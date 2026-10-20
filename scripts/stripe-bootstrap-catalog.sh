#!/usr/bin/env bash
# ======================================================================
# Stripe catalog bootstrap for Tapstate Cloud — via the Stripe CLI
#
# Creates ONE Stripe Product ("Tapstate Cluster") carrying one Price per
# platform spec:
#   Tapstate Cluster                metadata.catalog=tapstate
#     ├── $199/mo  USD monthly      metadata.spec_key=2c8g
#     ├── $399/mo  USD monthly      metadata.spec_key=4c16g
#     └── $799/mo  USD monthly      metadata.spec_key=8c32g
#
# Prerequisites:
#   Stripe CLI installed and logged in:
#     brew install stripe/stripe-cli/stripe
#     stripe login
#   The script resolves the binary from PATH or ~/.stripe/bin/stripe.
#
# Usage:
#   ./scripts/stripe-bootstrap-catalog.sh
#   LIVE=1 ./scripts/stripe-bootstrap-catalog.sh   # same, in live mode
#   DRY_RUN=1 ./scripts/stripe-bootstrap-catalog.sh  # print commands only
#
# Behaviour:
#   - Idempotent: an existing Product / Price at the target amount is skipped.
#   - Prices are immutable in Stripe, so an amount change creates a NEW Price
#     and archives the old one (active=false). The backend requires exactly one
#     active Price per spec_key.
#   - Any other active Product tagged catalog=tapstate (the older
#     one-Product-per-spec layout) is retired, otherwise the same spec_key would
#     appear on two Products and the backend would reject the catalog.
# ======================================================================
set -euo pipefail

PRODUCT_NAME="Tapstate Cluster"

LIVE="${LIVE:-0}"
DRY_RUN="${DRY_RUN:-0}"

if [ "$LIVE" = "1" ]; then
  ENV_TAG="prod"
else
  ENV_TAG="test"
fi

# ----------------------------------------------------------------------
# Resolve the Stripe CLI binary
# ----------------------------------------------------------------------
STRIPE_BIN="${STRIPE_BIN:-}"
if [ -z "$STRIPE_BIN" ]; then
  if command -v stripe >/dev/null 2>&1; then
    STRIPE_BIN="$(command -v stripe)"
  elif [ -x "$HOME/.stripe/bin/stripe" ]; then
    STRIPE_BIN="$HOME/.stripe/bin/stripe"
  else
    echo "ERROR: Stripe CLI not found. Install it (brew install stripe/stripe-cli/stripe)" >&2
    echo "       or point STRIPE_BIN at the binary." >&2
    exit 1
  fi
fi

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq not installed" >&2; exit 1; }

# ----------------------------------------------------------------------
# Log helpers (stderr — stdout stays clean for nothing, but keeps ordering sane)
# ----------------------------------------------------------------------
HEADER="\033[1;36m"; OK="\033[1;32m"; WARN="\033[1;33m"; ERR="\033[1;31m"; RESET="\033[0m"
log()    { echo -e "${HEADER}[$(date +%H:%M:%S)]${RESET} $*" >&2; }
log_ok() { echo -e "${OK}[$(date +%H:%M:%S)] OK ${RESET}$*" >&2; }
log_warn(){ echo -e "${WARN}[$(date +%H:%M:%S)] WARN ${RESET}$*" >&2; }
log_err(){ echo -e "${ERR}[$(date +%H:%M:%S)] ERR ${RESET}$*" >&2; }

# ----------------------------------------------------------------------
# Run a stripe CLI command.
#   - stdout: the JSON response (the CLI's account banner goes to stderr)
#   - on failure: prints the CLI's own error text, then returns non-zero
# No `2>/dev/null`: a rejected flag or API error must be visible.
# ----------------------------------------------------------------------
STRIPE_ARGS=()
if [ "$LIVE" = "1" ]; then
  STRIPE_ARGS+=(--live)
fi

run_stripe() {
  local errfile out
  errfile="$(mktemp)"
  if out="$("$STRIPE_BIN" "$@" ${STRIPE_ARGS[@]+"${STRIPE_ARGS[@]}"} 2>"$errfile")"; then
    rm -f "$errfile"
    printf '%s' "$out"
    return 0
  else
    local rc=$?
    log_err "stripe $* — failed (exit $rc)"
    sed 's/^/    /' "$errfile" >&2
    rm -f "$errfile"
    return 1
  fi
}

# ----------------------------------------------------------------------
# Spec catalog (keys mirror tapstate-cloud spec-config.json)
# ----------------------------------------------------------------------
SPEC_KEYS=("2c8g"   "4c16g"    "8c32g")
SPEC_PRICE_CENTS=(19900  39900   79900)   # $199 / $399 / $799 monthly

echo >&2
log "Stripe CLI:  $STRIPE_BIN ($("$STRIPE_BIN" version 2>/dev/null | head -1))"
log "Mode:        $ENV_TAG (LIVE=$LIVE)"
log "Product:     $PRODUCT_NAME"
log "Prices:      ${#SPEC_KEYS[@]}  (\$199 / \$399 / \$799 per month)"
log "DRY_RUN:     $DRY_RUN"
echo >&2

# ----------------------------------------------------------------------
# Step 0: confirm which account the CLI is logged into
# ----------------------------------------------------------------------
log "Verifying Stripe CLI authentication..."
account_line="$("$STRIPE_BIN" whoami 2>/dev/null | grep -E '^Account ' || true)"
if [ -z "$account_line" ]; then
  log_err "Not logged in. Run: stripe login"
  exit 1
fi
log_ok "$account_line"

if [ "$LIVE" = "1" ]; then
  log_warn "LIVE mode — changes affect real customers."
fi

if [ "$DRY_RUN" = "1" ]; then
  echo >&2
  log "Planned commands:"
  log "  $STRIPE_BIN products create --name \"$PRODUCT_NAME\" -d metadata[catalog]=tapstate"
  for i in "${!SPEC_KEYS[@]}"; do
    log "  $STRIPE_BIN prices create --product <prod> --unit-amount ${SPEC_PRICE_CENTS[$i]} --currency usd --recurring.interval month -d metadata[spec_key]=${SPEC_KEYS[$i]}"
  done
  log "  $STRIPE_BIN prices update <old_price> --active=false   # when an amount changes"
  log "  $STRIPE_BIN products update <old_product> --active=false   # legacy products"
  echo >&2
  exit 0
fi

# ----------------------------------------------------------------------
# Step 1: ensure the single catalog Product exists
# ----------------------------------------------------------------------
log "Listing products tagged catalog=tapstate..."
products_json="$(run_stripe products list --limit 100 --active=true)" || exit 1

product_id="$(printf '%s' "$products_json" | jq -r --arg n "$PRODUCT_NAME" \
  '[.data[]? | select((.metadata.catalog // "") == "tapstate" and .name == $n) | .id] | first // empty')"

if [ -n "$product_id" ]; then
  log_ok "Product exists: $product_id ($PRODUCT_NAME)"
else
  log "Creating Product '$PRODUCT_NAME'..."
  product_json="$(run_stripe products create \
    --name "$PRODUCT_NAME" \
    --description "Tapstate Cloud cluster — all instance specs" \
    -d "metadata[catalog]=tapstate" \
    -d "metadata[tapstate_env]=${ENV_TAG}")" || exit 1
  product_id="$(printf '%s' "$product_json" | jq -r '.id // empty')"
  if [ -z "$product_id" ]; then
    log_err "Product create returned no id — raw response:"
    printf '%s\n' "$product_json" | sed 's/^/    /' >&2
    exit 1
  fi
  log_ok "Product created: $product_id"
fi
echo >&2

# ----------------------------------------------------------------------
# Step 2: one Price per spec under that Product
# ----------------------------------------------------------------------
created_prices=0; rotated_prices=0; up_to_date=0

prices_json="$(run_stripe prices list --product "$product_id" --limit 100 --active=true)" || exit 1

for i in "${!SPEC_KEYS[@]}"; do
  spec_key="${SPEC_KEYS[$i]}"
  target_cents="${SPEC_PRICE_CENTS[$i]}"

  log "--- $spec_key — \$$((target_cents / 100))/mo ---"

  existing_price="$(printf '%s' "$prices_json" | jq -r --arg sk "$spec_key" \
    '[.data[]? | select((.metadata.spec_key // "") == $sk) | .id] | first // empty')"

  existing_amount=""
  if [ -n "$existing_price" ]; then
    existing_amount="$(printf '%s' "$prices_json" | jq -r --arg id "$existing_price" \
      '.data[]? | select(.id == $id) | .unit_amount')"
  fi

  if [ -n "$existing_price" ] && [ "$existing_amount" = "$target_cents" ]; then
    log_ok "Up to date: $existing_price (\$$((existing_amount / 100))/mo)"
    up_to_date=$((up_to_date + 1))
    echo >&2
    continue
  fi

  if [ -n "$existing_price" ]; then
    log "Amount changed: \$$((existing_amount / 100)) → \$$((target_cents / 100))/mo — creating a new Price"
  else
    log "Creating Price: \$$((target_cents / 100))/mo USD monthly"
  fi

  price_json="$(run_stripe prices create \
    --product "$product_id" \
    --unit-amount "$target_cents" \
    --currency usd \
    --recurring.interval month \
    -d "metadata[catalog]=tapstate" \
    -d "metadata[spec_key]=${spec_key}" \
    -d "metadata[tapstate_env]=${ENV_TAG}")" || continue

  new_price_id="$(printf '%s' "$price_json" | jq -r '.id // empty')"
  if [ -z "$new_price_id" ]; then
    log_err "Price create returned no id — raw response:"
    printf '%s\n' "$price_json" | sed 's/^/    /' >&2
    continue
  fi
  log_ok "Price created: $new_price_id"
  created_prices=$((created_prices + 1))

  # Archive the superseded Price — the backend catalog requires exactly one
  # active Price per spec_key, otherwise refreshAll() refuses to sync.
  if [ -n "$existing_price" ]; then
    if run_stripe prices update "$existing_price" --active=false >/dev/null; then
      log_ok "Old price archived: $existing_price"
      rotated_prices=$((rotated_prices + 1))
    else
      log_err "Failed to archive $existing_price — the backend will see two active prices for spec_key=$spec_key"
    fi
  fi
  echo >&2
done

# ----------------------------------------------------------------------
# Step 3: retire any other active tapstate Product (legacy layout)
# ----------------------------------------------------------------------
retired_products=0
other_ids="$(printf '%s' "$products_json" | jq -r --arg id "$product_id" \
  '.data[]? | select((.metadata.catalog // "") == "tapstate" and .id != $id) | .id')"

if [ -n "$other_ids" ]; then
  log "Retiring other tapstate products (legacy one-Product-per-spec layout):"
  while IFS= read -r old_id; do
    [ -z "$old_id" ] && continue
    if run_stripe products update "$old_id" --active=false >/dev/null; then
      log_ok "  retired: $old_id"
      retired_products=$((retired_products + 1))
    else
      log_err "  failed to retire $old_id — duplicate spec_key is still possible"
    fi
  done <<< "$other_ids"
  echo >&2
fi

# ----------------------------------------------------------------------
# Summary + verification listing
# ----------------------------------------------------------------------
log_ok "=== Bootstrap summary ==="
log "  Product:            $PRODUCT_NAME ($product_id)"
log "  Prices created:     $created_prices"
log "  Prices rotated:     $rotated_prices"
log "  Prices up to date:  $up_to_date"
log "  Products retired:   $retired_products"
echo >&2

log "Verifying from Stripe (active products / prices tagged catalog=tapstate):"
verify_products="$(run_stripe products list --limit 100 --active=true)" || exit 1
verify_prices="$(run_stripe prices list --limit 100 --active=true --type recurring)" || exit 1

active_ids="$(printf '%s' "$verify_products" | jq -c \
  '[.data[]? | select((.metadata.catalog // "") == "tapstate") | .id]')"

printf '%s' "$verify_products" | jq -r '.data[]?
  | select((.metadata.catalog // "") == "tapstate")
  | "  \(.id)  \(.name)  env=\(.metadata.tapstate_env // "?")"'

printf '%s' "$verify_prices" | jq -r --argjson ids "$active_ids" '.data[]?
  | select((.metadata.catalog // "") == "tapstate" and (.product as $p | $ids | index($p)))
  | "  \(.id)  \(.unit_amount / 100) \(.currency)/\(.recurring.interval)  spec_key=\(.metadata.spec_key // "none")"'

echo >&2
log_ok "Done. Dashboard: https://dashboard.stripe.com/${ENV_TAG}/products"
echo >&2
