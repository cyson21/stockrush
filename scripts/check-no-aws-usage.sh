#!/usr/bin/env bash
# AWS 의존 경로가 CI/데모 흐름에 새로 유입되지 않았는지 점검합니다.
set -euo pipefail

PATTERN='aws-actions|configure-aws|amazonaws\.com|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|AWS_SESSION_TOKEN|CODEBUILD|CODEPIPELINE|aws[[:space:]]+(s3|ecr|sts|configure)'
TARGETS=(".github" "scripts" "infra/demo")

if grep -R -n -E -i --exclude=check-no-aws-usage.sh "$PATTERN" "${TARGETS[@]}"; then
  printf 'AWS-specific CI/CD reference found. StockRush CI/CD must stay cloud-neutral.\n' >&2
  exit 1
fi

printf 'No AWS-specific CI/CD references found.\n'
