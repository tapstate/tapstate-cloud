#!/usr/bin/env bash
#
# setup-branch-protection.sh — create or update the `main` repository ruleset for
# the tapstate-cloud repository.
#
# Idempotent and safe to re-run. It looks the ruleset up by name: when it
# already exists the script updates it, otherwise it creates it. The ruleset
# protects the default branch (`main`) with:
#
#   * pull_request       — 1 approving review required, CODEOWNERS review
#                          required, stale reviews NOT dismissed, last-push
#                          approval NOT required
#   * non_fast_forward   — no force pushes
#   * deletion           — the branch cannot be deleted
#
# No required status checks are configured on purpose: a required check that is
# renamed, flaky, or path-filtered would block every merge on `main`. CI is
# enforced through the deploy gate and through review, not through this ruleset.
#
# `bypass_actors` grants the repository admin role (RepositoryRole id 5) an
# always-on bypass, so an administrator can still merge during an emergency.
#
# Usage:
#   scripts/setup-branch-protection.sh             # print a summary, then apply
#   scripts/setup-branch-protection.sh --dry-run    # print the JSON payload, change nothing
#
# Requires `gh` authenticated against the target repository. The repository slug
# defaults to the `origin` remote and can be overridden with REPO=owner/name.
#
# NOTE: this script is intentionally NOT executed as part of any workflow.

set -uo pipefail

RULESET_NAME="main-protection"
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    *)
      printf 'Unknown argument: %s\nUsage: %s [--dry-run]\n' "$arg" "$0" >&2
      exit 2
      ;;
  esac
done

# ---------------------------------------------------------------------------
# Target repository
# ---------------------------------------------------------------------------
if [ -z "${REPO:-}" ]; then
  REPO="$(git remote get-url origin 2>/dev/null \
    | sed -E 's#^git@[^:]+:##; s#^https?://[^/]+/##; s#\.git$##')"
fi
if [ -z "${REPO:-}" ]; then
  echo "ERROR: cannot determine the repository; run with REPO=owner/name" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Ruleset payload — the single description of the desired state
# ---------------------------------------------------------------------------
read -r -d '' PAYLOAD <<EOF || true
{
  "name": "$RULESET_NAME",
  "target": "branch",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["~DEFAULT_BRANCH"],
      "exclude": []
    }
  },
  "bypass_actors": [
    {
      "actor_id": 5,
      "actor_type": "RepositoryRole",
      "bypass_mode": "always"
    }
  ],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": true,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
      }
    }
  ]
}
EOF

echo "Repository ruleset : $RULESET_NAME"
echo "Repository         : $REPO"
echo "Target             : branch default (~DEFAULT_BRANCH = main)"
echo "Rules              : pull_request (1 approval, CODEOWNERS review required), non_fast_forward, deletion"
echo "Status checks      : none (intentionally)"
echo "Bypass             : RepositoryRole id 5 (repository admin), mode always"

# ---------------------------------------------------------------------------
# Dry run — print what would be sent, issue no write call
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
  echo "Action             : dry run (no API write)"
  echo "----- payload -----"
  printf '%s\n' "$PAYLOAD"
  exit 0
fi

command -v gh >/dev/null 2>&1 || { echo "ERROR: gh CLI not found" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Look up an existing ruleset of the same name
# ---------------------------------------------------------------------------
existing_ids=""
if ! existing_ids="$(gh api "repos/$REPO/rulesets" \
      -q ".[] | select(.name == \"$RULESET_NAME\") | .id" 2>/dev/null)"; then
  echo "ERROR: cannot list rulesets for $REPO (is gh authenticated?)" >&2
  exit 1
fi
existing_id="$(printf '%s\n' "$existing_ids" | head -n1)"

if [ -n "$existing_id" ]; then
  echo "Action             : update existing ruleset id $existing_id"
  # GitHub's ruleset update endpoint is PUT; there is no PATCH for rulesets.
  gh api --method PUT "repos/$REPO/rulesets/$existing_id" \
    -H "Accept: application/vnd.github+json" --input - <<<"$PAYLOAD" >/dev/null \
    || { echo "ERROR: failed to update ruleset $existing_id" >&2; exit 1; }
else
  echo "Action             : create new ruleset"
  gh api --method POST "repos/$REPO/rulesets" \
    -H "Accept: application/vnd.github+json" --input - <<<"$PAYLOAD" >/dev/null \
    || { echo "ERROR: failed to create ruleset" >&2; exit 1; }
fi

echo "OK: ruleset '$RULESET_NAME' applied to $REPO"
