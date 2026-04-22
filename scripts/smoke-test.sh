#!/usr/bin/env bash
# Post-deploy smoke test. Called by Jenkins after a rollout completes.
# Usage: ./scripts/smoke-test.sh <base-url>
#   e.g. ./scripts/smoke-test.sh https://ems-dev.example.internal

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
MAX_ATTEMPTS=30
SLEEP_SECS=5

echo "==> Smoke testing ${BASE_URL}"

# 1. Liveness — the pod is reachable
for i in $(seq 1 $MAX_ATTEMPTS); do
  if curl -fsS "${BASE_URL}/actuator/health/liveness" > /dev/null; then
    echo "    [OK] liveness"
    break
  fi
  if [ "$i" -eq "$MAX_ATTEMPTS" ]; then
    echo "    [FAIL] liveness never passed"
    exit 1
  fi
  sleep "$SLEEP_SECS"
done

# 2. Readiness — dependencies (DB, Redis, Kafka) are up
curl -fsS "${BASE_URL}/actuator/health/readiness" > /dev/null
echo "    [OK] readiness"

# 3. Business endpoint — a real path through the stack
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/v1/departments")
if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "204" ]; then
  echo "    [FAIL] GET /api/v1/departments returned ${HTTP_CODE}"
  exit 1
fi
echo "    [OK] GET /api/v1/departments (${HTTP_CODE})"

echo "==> Smoke test passed"
