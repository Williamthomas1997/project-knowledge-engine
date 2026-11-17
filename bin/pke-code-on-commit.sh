#!/usr/bin/env sh
# Runs the code-on-commit pipeline (M5) and waits for it. For move-to-dev/UAT/pilot skills and CI.
# Usage: bin/pke-code-on-commit.sh <repository> [toCommit] [tag] [fromCommit]
#   PKE_URL (default http://localhost:8080), PKE_ACTOR (recorded on change events).
# Exit code 0 when the execution COMPLETED, 1 otherwise. Prints the execution JSON.
set -eu
repo="${1:?usage: pke-code-on-commit.sh <repository> [toCommit] [tag] [fromCommit]}"
body="{\"repository\":\"$repo\""
[ -n "${2:-}" ] && body="$body,\"toCommit\":\"$2\""
[ -n "${3:-}" ] && body="$body,\"tag\":\"$3\""
[ -n "${4:-}" ] && body="$body,\"fromCommit\":\"$4\""
body="$body}"
out=$(curl -sS -X POST "${PKE_URL:-http://localhost:8080}/api/pipelines/code-on-commit?wait=true" \
  -H 'Content-Type: application/json' -H "X-PKE-Actor: ${PKE_ACTOR:-cli}" --data "$body")
echo "$out"
# the execution's own status is the first "status" field (steps follow it)
[ "$(printf '%s' "$out" | grep -o '"status":"[A-Z_]*"' | head -n 1)" = '"status":"COMPLETED"' ]
