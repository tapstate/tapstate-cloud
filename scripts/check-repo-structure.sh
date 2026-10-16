#!/usr/bin/env bash
#
# check-repo-structure.sh — repository governance baseline assertions.
#
# Declarative and side-effect free. Uses only grep/sed/core utilities. Prints one
# clear reason line per failure and exits non-zero when anything fails.
#
# Assertions:
#   1. Every module directory listed in the root pom.xml <modules> exists and is
#      not empty.
#   2. Every real production path has a CODEOWNERS rule other than the `*`
#      fallback. The fallback is a safety net, not ownership, so it never
#      satisfies this assertion on its own.
#   3. Every owner handle (`@...`) that appears in CODEOWNERS is in the allowed
#      set below. An unknown handle silently disables the rule, so it must be
#      red — this is what stops a non-existent team from being written again.
#   4. Every shared contract surface has at least two distinct owners, so a
#      contract change is reviewed by both the provider and the consumer side.
#
# The path and owner lists below are the single declarative source of truth for
# this script; add an entry here in the same change that adds it to CODEOWNERS.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR" || exit 1

# ---------------------------------------------------------------------------
# Declarative inputs
# ---------------------------------------------------------------------------
CODEOWNERS_FILE="CODEOWNERS"
POM_FILE="pom.xml"

# Handles that exist in the organization. Any other `@handle` is a failure.
ALLOWED_OWNERS=(
  "@tapstate/dev"
  "@ply0011"
)

# Real production paths: each must have a named (non-`*`) CODEOWNERS rule.
PRODUCTION_PATHS=(
  "cloud-control-plane/"
  "common/"
  "client/java/"
  "helm/"
  ".github/workflows/"
  "Dockerfile"
  "pom.xml"
)

# Shared contract surface: each must have at least two distinct owners.
CONTRACT_PATHS=(
  "cloud-control-plane/src/main/resources/contract-fixtures/"
  "client/java/"
  "client/api/openapi.yaml"
)

# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------
failures=0
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
# Normalize a pattern or path for comparison: drop a leading and trailing slash.
normalize() {
  local p="$1"
  p="${p#/}"
  p="${p%/}"
  printf '%s' "$p"
}

# Does normalized pattern $1 match normalized path $2?
pattern_matches() {
  local pat="$1" path="$2"
  [ "$pat" = "$path" ] && return 0
  case "$path" in
    "$pat"/*) return 0 ;;
  esac
  return 1
}

# Print the owners of the LAST matching rule for $1 (GitHub's precedence).
# When $2 is "named", the `*` fallback is ignored.
matching_owners() {
  local wanted line pat owners npat result=""
  wanted="$(normalize "$1")"
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    pat="${line%% *}"
    owners="${line#* }"
    if [ "$pat" = "*" ]; then
      [ "${2:-}" = "named" ] && continue
      result="$owners"
      continue
    fi
    npat="$(normalize "$pat")"
    if pattern_matches "$npat" "$wanted"; then
      result="$owners"
    fi
  done <<EOF
$rules
EOF
  printf '%s' "$result"
}

# Count the distinct whitespace-separated tokens in $1.
count_distinct() {
  local seen=" " n=0 tok
  for tok in $1; do
    case "$seen" in
      *" $tok "*) ;;
      *) seen="$seen$tok "; n=$((n + 1)) ;;
    esac
  done
  printf '%s' "$n"
}

# ---------------------------------------------------------------------------
# Load CODEOWNERS rules (strip comments/blank lines, collapse whitespace)
# ---------------------------------------------------------------------------
if [ ! -f "$CODEOWNERS_FILE" ]; then
  printf 'FAIL: %s not found\n' "$CODEOWNERS_FILE" >&2
  exit 1
fi
rules="$(grep -vE '^[[:space:]]*(#|$)' "$CODEOWNERS_FILE" \
  | sed -E 's/^[[:space:]]+//; s/[[:space:]]+/ /g')"

if [ -z "$rules" ]; then
  fail "$CODEOWNERS_FILE has no rules"
fi

# ---------------------------------------------------------------------------
# Assertion 1 — every declared Maven module exists and is non-empty
# ---------------------------------------------------------------------------
if [ ! -f "$POM_FILE" ]; then
  fail "$POM_FILE not found"
else
  modules="$(grep -oE '<module>[^<]+</module>' "$POM_FILE" \
    | sed -E 's#<module>##g; s#</module>##g')"
  if [ -z "$modules" ]; then
    fail "$POM_FILE declares no <module> entries"
  fi
  for module in $modules; do
    if [ ! -d "$module" ]; then
      fail "module directory '$module' (declared in $POM_FILE) does not exist"
    elif [ -z "$(ls -A "$module" 2>/dev/null)" ]; then
      fail "module directory '$module' (declared in $POM_FILE) is empty"
    fi
  done
fi

# ---------------------------------------------------------------------------
# Assertion 3 — every owner handle is in the allowed set
# ---------------------------------------------------------------------------
while IFS= read -r line; do
  [ -z "$line" ] && continue
  for tok in ${line#* }; do
    case "$tok" in
      @*)
        allowed=0
        for ok in "${ALLOWED_OWNERS[@]}"; do
          [ "$tok" = "$ok" ] && allowed=1
        done
        if [ "$allowed" -ne 1 ]; then
          fail "unknown owner '$tok' in $CODEOWNERS_FILE (allowed: ${ALLOWED_OWNERS[*]})"
        fi
        ;;
    esac
  done
done <<EOF
$rules
EOF

# ---------------------------------------------------------------------------
# Assertion 2 — every production path has a named rule
# ---------------------------------------------------------------------------
for path in "${PRODUCTION_PATHS[@]}"; do
  owners="$(matching_owners "$path" named)"
  if [ -z "$owners" ]; then
    fail "production path '$path' has no CODEOWNERS rule other than the '*' fallback"
  fi
done

# ---------------------------------------------------------------------------
# Assertion 4 — every contract surface has at least two distinct owners
# ---------------------------------------------------------------------------
for path in "${CONTRACT_PATHS[@]}"; do
  owners="$(matching_owners "$path")"
  if [ -z "$owners" ]; then
    fail "shared contract surface '$path' has no CODEOWNERS rule"
    continue
  fi
  distinct="$(count_distinct "$owners")"
  if [ "$distinct" -lt 2 ]; then
    fail "shared contract surface '$path' has only $distinct owner(s) in $CODEOWNERS_FILE; provider and consumer must both review it"
  fi
done

# ---------------------------------------------------------------------------
# Result
# ---------------------------------------------------------------------------
if [ "$failures" -eq 0 ]; then
  echo "OK: repository structure checks passed"
  exit 0
fi
printf 'FAILED: %d repository structure problem(s)\n' "$failures" >&2
exit 1
