#!/usr/bin/env bash
set -euo pipefail

PATTERN='-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----|github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{32,}|xox[baprs]-[A-Za-z0-9-]{20,}'
TARGETS=(
  ".ai-runs"
  ".github"
  "apps"
  "docs"
  "infra"
  "scripts"
  "services"
  "tools"
  "README.md"
  "TODO.md"
)
EXCLUDES=(
  ":(exclude)**/node_modules/**"
  ":(exclude)**/target/**"
  ":(exclude)**/build/**"
  ":(exclude)**/dist/**"
  ":(exclude)**/.pytest_cache/**"
  ":(exclude)**/__pycache__/**"
  ":(exclude)**/package-lock.json"
)

set +e
git grep -nI -E -e "$PATTERN" -- "${TARGETS[@]}" "${EXCLUDES[@]}"
status=$?
set -e

if [[ $status -eq 0 ]]; then
  printf 'Potential committed secret found. Remove the value or replace it with a safe placeholder.\n' >&2
  exit 1
fi

if [[ $status -gt 1 ]]; then
  printf 'Secret scan failed before completing.\n' >&2
  exit "$status"
fi

printf 'No high-confidence committed secret patterns found.\n'
