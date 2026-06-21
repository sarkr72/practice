#!/usr/bin/env bash
#
# Deploy / update the platform (terraform). App image deploys go through
# .github/workflows/deploy.yml (push -> Jib -> ECR -> ECS update-service),
# NOT through this script.
#
# Usage:
#   ./scripts/deploy.sh <env>          # apply
#   ./scripts/deploy.sh <env> destroy  # tear down (graph-ordered, one command)
#
# What this script touches:
#   ECR repo, ALB, target groups, ECS cluster + service, IAM roles, RDS, secrets.
# What this script does NOT touch:
#   Task-definition revisions — those belong to the deploy workflow. Terraform
#   provides only the bootstrap revision and ignores the service's task_definition.

set -euo pipefail

cd "$(dirname "$0")/../terraform/ems"

ENV="${1:?usage: $0 <env> [destroy]}"
ARG2="${2:-}"

case "$ENV" in
  dev|perf|prod) ;;
  *) echo "env must be one of: dev, perf, prod" >&2; exit 1 ;;
esac

VAR_FILE="envs/${ENV}.tfvars"
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
