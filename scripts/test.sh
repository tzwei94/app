#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${BANK_TEST_DB_URL:-}" ]]; then
  name="banking-test-$(date +%s)-$$"
  export POSTGRES_PASSWORD
  POSTGRES_PASSWORD="$(openssl rand -hex 24)"
  docker run -d --name "$name" -e POSTGRES_PASSWORD -e POSTGRES_USER=banking_test -e POSTGRES_DB=banking_test -p 127.0.0.1::5432 postgres:17.9-alpine >/dev/null
  trap 'docker rm -f "$name" >/dev/null' EXIT
  for _ in {1..30}; do docker exec "$name" pg_isready -U banking_test >/dev/null 2>&1 && break; sleep 1; done
  docker exec "$name" pg_isready -U banking_test >/dev/null
  port="$(docker port "$name" 5432/tcp | awk -F: '{print $NF}')"
  export BANK_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$port/banking_test"
  export BANK_TEST_DB_PASSWORD="$POSTGRES_PASSWORD"
fi
./mvnw -B verify "$@"
