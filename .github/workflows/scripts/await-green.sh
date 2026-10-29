#!/usr/bin/env bash
# Gate for CD: block until the CI run triggered by the push of this exact
# SHA has succeeded. On main branches it also waits for the Full Integration
# run while that workflow is switched on (repository variable) and triggered.
# Release branches (release-v*) only require CI — Full Integration is never
# triggered from them. Fail fast on any failure or cancellation; time out
# after GATE_TIMEOUT_S.
#
# Cross-workflow `needs` does not exist in GitHub Actions, so the CD
# workflow polls the Actions runs for its own commit rather than guessing
# from another workflow's job graph.
#
# Required environment (provided by the GitHub Actions runner):
#   GITHUB_SHA, GITHUB_REPOSITORY, GH_TOKEN
# Optional:
#   GITHUB_REF_NAME the pushed branch name — used to decide whether Full IT
#                  is part of the gate (release-v* skips it)
#   GATE_TIMEOUT_S  overall wait deadline (default 2400 = 40 minutes)
#   GATE_POLL_S     polling interval in seconds (default 30)
#   FULL_INTEGRATION_ENABLED
#                   repository variable forwarded from deploy.yaml; the
#                   Full Integration wait applies only while it is the
#                   exact string "true" AND the branch is main — the
#                   same condition the workflow itself is gated on
set -euo pipefail

: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"

TIMEOUT_S="${GATE_TIMEOUT_S:-2400}"
POLL_S="${GATE_POLL_S:-30}"
BRANCH="${GITHUB_REF_NAME:-}"

# Full Integration is required only when BOTH hold:
#   1. the repository switch FULL_INTEGRATION_ENABLED is "true" (every job
#      in full-integration.yml is skipped otherwise), and
#   2. this push touches one of its path-filtered triggers.
# This applies uniformly to main, develop and release-v* branches — the
# workflow push-trigger covers all three, so the gate waits wherever it
# could actually run. Keep the pathspec list in sync with the &it_paths
# anchor in full-integration.yml.
needs_full_it=1
if [[ "${FULL_INTEGRATION_ENABLED:-}" != "true" ]]; then
  needs_full_it=0
  echo "gate: repository variable FULL_INTEGRATION_ENABLED is not 'true'; Full Integration is switched off — waiving Full IT"
elif ! git diff --name-only HEAD^ HEAD -- \
      ':(glob)**/src/main/**' \
      ':(glob)**/src/it/**' \
      ':(glob)**/pom.xml' \
      'client/api/openapi.yaml' \
      ':(glob)client/java/src/**' \
      ':(glob).github/workflows/**' | grep -q .; then
  needs_full_it=0
  echo "gate: no Full Integration trigger path in this push; waiving Full IT"
fi

# State of the newest push-triggered run of a workflow for this SHA:
# pending | success | failure | missing. A rerun creates a newer run
# number, which is the one that counts.
run_state() {
  local workflow="$1"
  local filter=".workflow_runs
    | map(select(.name == \"${workflow}\"))
    | sort_by(.run_number)
    | last
    | if . == null then \"missing\"
      elif .status != \"completed\" then \"pending\"
      elif .conclusion == \"success\" then \"success\"
      else \"failure\" end"
  gh api \
    -H "Accept: application/vnd.github+json" \
    "/repos/${GITHUB_REPOSITORY}/actions/runs?head_sha=${GITHUB_SHA}&event=push&per_page=100" \
    --jq "$filter"
}

deadline=$(( SECONDS + TIMEOUT_S ))
while (( SECONDS < deadline )); do
  ci_state="$(run_state "CI")"
  full_state="$(run_state "Full Integration")"
  if (( needs_full_it == 0 )); then
    full_state=success
  fi
  echo "gate: CI=${ci_state} FullIntegration=${full_state} (sha=${GITHUB_SHA:0:8})"

  if [[ "$ci_state" == "failure" || "$full_state" == "failure" ]]; then
    echo "gate: a required workflow failed; deployment to awsdev is blocked" >&2
    exit 1
  fi
  if [[ "$ci_state" == "success" && "$full_state" == "success" ]]; then
    echo "gate: all required workflows green for ${GITHUB_SHA}; proceeding to deploy"
    exit 0
  fi
  sleep "$POLL_S"
done

echo "gate: timed out after ${TIMEOUT_S}s waiting for required workflows" >&2
exit 1
