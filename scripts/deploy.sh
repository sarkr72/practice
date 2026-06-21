#!/usr/bin/env bash
#
# Apply / destroy the EMS infrastructure across BOTH layers, in the correct
# order. App *image* deploys still go through .github/workflows/deploy.yml
# (push -> Jib -> ECR -> ECS update-service), NOT this script.
#
#   apply:   platform (terraform/ems)      ->  app (terraform/ems-app)
#   destroy: app (terraform/ems-app)       ->  platform (terraform/ems)
#
# The order is the whole point of the layer split: you can destroy just the app
# (`./scripts/deploy.sh dev destroy-app`) to stop the bill while leaving the
# cluster, ALB, and database untouched in the platform layer.
#
# Usage:
#   ./scripts/deploy.sh <env>               # apply platform, then app
#   ./scripts/deploy.sh <env> destroy       # destroy app, then platform
#   ./scripts/deploy.sh <env> destroy-app   # destroy ONLY the app layer

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ENV="${1:?usage: $0 <env> [destroy|destroy-app]}"
ACTION="${2:-apply}"

case "$ENV" in
  dev|perf|prod) ;;
  *) echo "env must be one of: dev, perf, prod" >&2; exit 1 ;;
esac

run_layer() {
  local dir="$1" tf_action="$2"
  echo ""
  echo "==> [$tf_action] $dir (env=${ENV})"
  cd "$ROOT/$dir"
  terraform init -input=false
  terraform workspace select "$ENV" 2>/dev/null || terraform workspace new "$ENV"
  terraform "$tf_action" -var-file="envs/${ENV}.tfvars" -auto-approve
}

case "$ACTION" in
  apply)
    run_layer "terraform/ems"     apply   # platform first (creates the outputs)
    run_layer "terraform/ems-app" apply   # then app (reads them via remote state)
    echo ""
    echo "==> Outputs (app layer):"
    terraform output
    echo ""
    echo "==> Platform + app applied. Image deploys: git push -> deploy.yml."
    ;;
  destroy)
    run_layer "terraform/ems-app" destroy # app first (drains tasks, frees ENIs)
    run_layer "terraform/ems"     destroy # then platform (cluster, ALB, RDS, ...)
    ;;
  destroy-app)
    run_layer "terraform/ems-app" destroy # ONLY the app; platform + DB stay up
    echo ""
    echo "==> App layer destroyed. Platform (cluster, ALB, RDS) is still running."
    ;;
  *)
    echo "action must be one of: apply (default), destroy, destroy-app" >&2
    exit 1
    ;;
esac
