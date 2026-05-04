#!/usr/bin/env bash
#
# Smoke test - hits the deployed app's actuator endpoints to verify a deploy succeeded.
# Called by Jenkinsfile's "Smoke Test Dev" and "Smoke Test Prod" stages.
#
# Usage: ./scripts/smoke-test.sh <base-url>
# Example: ./scripts/smoke-test.sh https://ems-dev.example.internal

set -euo pipefail

BASE_URL="${1:?usage: $0 <base-url>}"
MAX_ATTEMPTS=30
SLEEP_SECONDS=2

echo "==> Smoke testing ${BASE_URL}"

check_endpoint() {
    local path="$1"
    local expected_status="${2:-200}"
    local url="${BASE_URL}${path}"

    local attempt=0
    while [ $attempt -lt $MAX_ATTEMPTS ]; do
        local status
        status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" || echo "000")

        if [ "$status" = "$expected_status" ]; then
            echo "  ✓ ${path} -> ${status}"
            return 0
        fi

        attempt=$((attempt + 1))
        if [ $attempt -lt $MAX_ATTEMPTS ]; then
            echo "  ... ${path} -> ${status} (attempt ${attempt}/${MAX_ATTEMPTS})"
            sleep $SLEEP_SECONDS
        fi
    done

    echo "  ✗ ${path} -> ${status} (FAILED after ${MAX_ATTEMPTS} attempts)"
    return 1
}

# Liveness: pod is up and JVM is healthy
check_endpoint "/actuator/health/liveness" 200

# Readiness: pod can serve traffic (DB + Redis reachable)
check_endpoint "/actuator/health/readiness" 200

# Overall health
check_endpoint "/actuator/health" 200

# A real domain endpoint — canary check on the API surface.
# Expects either 200 (list returned) or 401 if auth is enabled in prod.
# Adjust once real auth is wired up.
check_endpoint "/api/v1/departments" 200

echo "==> Smoke test PASSED"
