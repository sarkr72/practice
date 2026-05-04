#!/usr/bin/env bash
#
# Deploy / update the platform (terraform). App deploys go through Spinnaker
# via Jenkins → Jib → ECR → webhook → Spinnaker, NOT through this script.
#
# Usage:
#   ./scripts/deploy.sh <env>          # apply
#   ./scripts/deploy.sh <env> destroy  # tear down
#
# What this script touches:
#   ECR repo, ALB, target groups, ECS cluster, IAM roles, RDS, secrets.
# What this script does NOT touch:
#   ECS services / task definitions — those are Spinnaker's job.

set -euo pipefail

cd "$(dirname "$0")/../terraform"

ENV="${1:?usage: $0 <env> [destroy]}"
ARG2="${2:-}"

case "$ENV" in
  dev|prod) ;;
  *) echo "env must be one of: dev, prod" >&2; exit 1 ;;
esac

VAR_FILE="variables/${ENV}.tfvars"
if [[ ! -f "$VAR_FILE" ]]; then
  echo "Missing $VAR_FILE" >&2
  exit 1
fi

echo "==> terraform init"
terraform init -input=false

echo "==> Selecting workspace: ${ENV}"
terraform workspace select "$ENV" 2>/dev/null || terraform workspace new "$ENV"

if [[ "$ARG2" == "destroy" ]]; then
  echo "==> terraform destroy (env=${ENV})"
  terraform destroy -var-file="$VAR_FILE" -auto-approve
  exit 0
fi

echo "==> terraform apply (env=${ENV})"
terraform apply -var-file="$VAR_FILE" -auto-approve

echo ""
echo "==> Outputs:"
terraform output

echo ""
echo "==> Platform ready. App deploys are now triggered by:"
echo "    git push   →   Jenkins   →   Jib pushes to ECR   →   webhook   →   Spinnaker"
