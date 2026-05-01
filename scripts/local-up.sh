#!/usr/bin/env bash
# Brings up the local dev stack and waits for everything to be healthy.
# Usage: ./scripts/local-up.sh

set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Starting local stack (mysql, redis, kafka, kafka-ui)..."
docker compose up -d

echo "==> Waiting for MySQL..."
until docker compose exec -T mysql mysqladmin ping -h localhost -uems -pems --silent 2>/dev/null; do
  sleep 2
done

echo "==> Waiting for Redis..."
until docker compose exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; do
  sleep 2
done

echo "==> Waiting for Kafka..."
until docker compose exec -T kafka kafka-topics --bootstrap-server localhost:29092 --list >/dev/null 2>&1; do
  sleep 3
done

echo ""
echo "==> Stack ready!"
echo "    MySQL    : localhost:3307  (user: ems, password: ems, db: ems)"
echo "    Redis    : localhost:6379"
echo "    Kafka    : localhost:9092"
echo "    Kafka UI : http://localhost:8090"
echo ""
echo "Run the app: ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"
