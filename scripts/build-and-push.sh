#!/usr/bin/env bash
#
# Build the Docker image and push it to ECR.
# Run AFTER `terraform apply` (the ECR repo has to exist first).
#
# Usage:
#   ./scripts/build-and-push.sh <env>           # tags as :latest and :<git-sha>
#   ./scripts/build-and-push.sh dev abc123      # explicit tag
#
# Env variables you can override:
#   AWS_REGION   (default: us-east-1)
#   ECR_REPO     (default: ems)

set -euo pipefail

cd "$(dirname "$0")/.."

ENV="${1:?usage: $0 <env> [tag]}"
TAG="${2:-$(git rev-parse --short HEAD 2>/dev/null || echo latest)}"

AWS_REGION="${AWS_REGION:-us-east-1}"
ECR_REPO="${ECR_REPO:-ems}"

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
IMAGE="${REGISTRY}/${ECR_REPO}"

echo "==> Logging in to ECR (${REGISTRY})"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"

echo "==> Building image ${IMAGE}:${TAG}"
GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

DOCKER_BUILDKIT=1 docker build \
  --build-arg GIT_SHA="$GIT_SHA" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --build-arg APP_VERSION="$TAG" \
  -t "${IMAGE}:${TAG}" \
  -t "${IMAGE}:latest" \
  -f Dockerfile \
  .

echo "==> Pushing ${IMAGE}:${TAG}"
docker push "${IMAGE}:${TAG}"
docker push "${IMAGE}:latest"

echo ""
echo "==> Done. Image pushed:"
echo "    ${IMAGE}:${TAG}"
echo "    ${IMAGE}:latest"
echo ""
echo "Next: ./scripts/deploy.sh ${ENV} ${TAG}"
