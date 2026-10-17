#!/usr/bin/env bash
#
# check-no-secrets.sh — fail if the working tree contains anything that looks
# like a committed secret.
#
# Scans every file in the working tree except the directories ignored below.
# Uses bash + grep -E and core utilities only. Side-effect free.
#
# Output is deliberately `file:line: <category>` — the matched value is NEVER
# printed. That is what lets this script run in CI without echoing a real
# secret into the build log, and it is itself an assertion: a found secret must
# not appear in this script's output.
#
# Categories:
#   - PEM private key block   a `-----BEGIN ... PRIVATE KEY-----` header closed
#                             by the matching END line with a base64 body
#                             between them (a bare header in source code that
#                             only builds the string is not a key block)
#   - AWS access key id       `AKIA` followed by 16 upper-case alphanumerics
#   - hardcoded password      a password/passwd assigned a quoted literal
#   - long base64/hex secret  a >=40-character token assigned to a
#                             secret-named identifier, excluding placeholders
#
# Exclusions: the `hardcoded password` and `long base64/hex secret` categories
# ignore lines containing a placeholder marker (`${...}`, changeme, your-,
# example, placeholder, test, dummy, xxxx, ...).
#
# Escape hatch: any line containing the token `nosecret` is skipped. Write it in
# the host language's comment syntax, e.g. `# nosecret` in YAML or `// nosecret`
# in Java. Use it only for a value that is provably not a real secret.

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR" || exit 1

# ---------------------------------------------------------------------------
# Directories never scanned
# ---------------------------------------------------------------------------
EXCLUDED_DIRS=(.git target node_modules build .terraform)

# ---------------------------------------------------------------------------
# Detection patterns
# ---------------------------------------------------------------------------
AWS_KEY='AKIA[0-9A-Z]{16}'
PASSWORD='password[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9][^"'"'"']{7,}'
LONG_VALUE='[=:][[:space:]]*["'"'"']?[A-Za-z0-9+]{40,}={0,2}'
SECRET_NAME='(secret|token|password|passwd|credential|api[_-]?key|private[_-]?key|master[_-]?key|signing[_-]?key|access[_-]?key|apikey)'
PLACEHOLDER='(\$\{|changeme|your[-_]|example|placeholder|test|dummy|redacted|nosecret|[xX]{4,}|\.\.\.)'

# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------
hits=0
report() {
  printf '%s:%s: %s\n' "$1" "$2" "$3"
  hits=$((hits + 1))
}

# 0 (skip) when the line must not be reported. $2 = 1 to apply placeholder
# exclusions as well.
should_skip() {
  local line="$1" use_placeholder="${2:-0}"
  printf '%s' "$line" | grep -qi 'nosecret' && return 0
  if [ "$use_placeholder" = 1 ]; then
    printf '%s' "$line" | grep -qiE "$PLACEHOLDER" && return 0
  fi
  return 1
}

# 0 when the line assigns a >= 40-character value (to a secret-named
# identifier) that carries both a digit and a letter. Requiring the value to sit
# right after `:` or `=` keeps long camelCase identifiers — type names, method
# names — from being mistaken for a secret.
line_has_secret_token() {
  local line="$1" match tok
  while IFS= read -r match; do
    # Drop everything up to and including the first separator, then any leading
    # whitespace, so only the assigned value is tested.
    tok="${match#*[=:]}"
    tok="${tok#"${tok%%[![:space:]]*}"}"
    printf '%s' "$tok" | grep -qE '[0-9]' || continue
    printf '%s' "$tok" | grep -qE '[A-Za-z]' || continue
    return 0
  done < <(printf '%s\n' "$line" | grep -oE "$LONG_VALUE")
  return 1
}

# ---------------------------------------------------------------------------
# Per-file scan
# ---------------------------------------------------------------------------
scan_file() {
  local f="$1" ln line

  # PEM private key block (multi-line: header + base64 body + footer).
  while IFS= read -r ln; do
    [ -n "$ln" ] || continue
    report "$f" "$ln" "PEM private key block"
  done < <(awk '
    FNR == 1 { inblk = 0; body = 0 }
    /-----BEGIN[A-Z0-9 ]*PRIVATE KEY[A-Z ]*-----/ { inblk = 1; body = 0; start = FNR; next }
    inblk == 1 && /-----END[A-Z0-9 ]*PRIVATE KEY[A-Z ]*-----/ { if (body) print start; inblk = 0; next }
    inblk == 1 && /^[A-Za-z0-9+\/=]{20,}$/ { body = 1 }
  ' "$f" 2>/dev/null)

  # AWS access key id.
  while IFS=: read -r ln line; do
    [ -n "$ln" ] || continue
    should_skip "$line" 0 && continue
    report "$f" "$ln" "AWS access key id"
  done < <(grep -nEI "$AWS_KEY" "$f" 2>/dev/null)

  # Hardcoded password.
  while IFS=: read -r ln line; do
    [ -n "$ln" ] || continue
    should_skip "$line" 1 && continue
    report "$f" "$ln" "hardcoded password"
  done < <(grep -nEI "$PASSWORD" "$f" 2>/dev/null)

  # Long base64/hex secret, only next to a secret-named identifier.
  while IFS=: read -r ln line; do
    [ -n "$ln" ] || continue
    should_skip "$line" 1 && continue
    printf '%s' "$line" | grep -qiE "$SECRET_NAME" || continue
    line_has_secret_token "$line" || continue
    report "$f" "$ln" "long base64/hex secret"
  done < <(grep -nEI "$LONG_VALUE" "$f" 2>/dev/null)
}

# ---------------------------------------------------------------------------
# Walk the working tree
# ---------------------------------------------------------------------------
find_expr=(. -type f)
for d in "${EXCLUDED_DIRS[@]}"; do
  find_expr+=(-not -path "./$d/*" -not -path "*/$d/*")
done

while IFS= read -r -d '' f; do
  [ -f "$f" ] || continue
  scan_file "$f"
done < <(find "${find_expr[@]}" -print0)

# ---------------------------------------------------------------------------
# Result
# ---------------------------------------------------------------------------
if [ "$hits" -eq 0 ]; then
  echo "OK: no committed secrets detected"
  exit 0
fi
printf 'FAILED: %d potential secret(s) found (categories only; values are not printed)\n' "$hits" >&2
exit 1
