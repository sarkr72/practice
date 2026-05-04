#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Add --volumes if you want to nuke mysql/redis data too.
docker compose down "$@"
