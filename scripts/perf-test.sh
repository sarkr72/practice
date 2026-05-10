#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# perf-test.sh — run the EMS load test against the perf environment.
#
# Modes:
#   local     : run JMeter locally via the Maven `perf-test` profile
#   blazemeter: run via Taurus/BlazeMeter cloud (requires bzt + API creds)
#
# Usage:
#   scripts/perf-test.sh local      [host] [port] [users] [duration]
#   scripts/perf-test.sh blazemeter [host] [port]
#
# Defaults target the perf environment internal hostname.
# ---------------------------------------------------------------------------
set -euo pipefail

MODE="${1:-local}"
HOST="${2:-ems-perf.internal}"
PORT="${3:-8080}"
USERS="${4:-50}"
DURATION="${5:-300}"

case "$MODE" in
  local)
    exec ./mvnw -B -ntp -Pperf-test verify \
      -DskipTests \
      -Dperf.host="$HOST" \
      -Dperf.port="$PORT" \
      -Dperf.users="$USERS" \
      -Dperf.duration="$DURATION"
    ;;

  blazemeter)
    : "${BLAZEMETER_API_KEY:?BLAZEMETER_API_KEY must be set}"
    : "${BLAZEMETER_API_SECRET:?BLAZEMETER_API_SECRET must be set}"
    exec bzt performance/blazemeter.yml -cloud \
      -o settings.env.host="$HOST" \
      -o settings.env.port="$PORT"
    ;;

  *)
    echo "Unknown mode: $MODE" >&2
    echo "Usage: $0 {local|blazemeter} [host] [port] [users] [duration]" >&2
    exit 2
    ;;
esac
