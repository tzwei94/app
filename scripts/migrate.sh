#!/usr/bin/env bash
# Run the application's Liquibase command in a disposable Docker container.
set -euo pipefail
: "${DB_URL:?Set DB_URL to a PostgreSQL JDBC URL reachable from the container}"
: "${DB_USERNAME:?Set DB_USERNAME to the migration database user}"
: "${DB_PASSWORD:?Set DB_PASSWORD to the migration database password}"
args=(--rm)
if [[ -n "${DOCKER_NETWORK:-}" ]]; then
  args+=(--network "$DOCKER_NETWORK")
fi
export DB_URL DB_USERNAME DB_PASSWORD
exec docker run "${args[@]}" \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD \
  -e "SEED_SYNTHETIC=${SEED_SYNTHETIC:-false}" \
  "${MIGRATION_IMAGE:-banking-api:local}" migrate
