#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p local-data
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out local-data/demo.key 2>/dev/null
JWT_PRIVATE_KEY="$(cat local-data/demo.key)"
TOKEN_USERNAME=smoke
TOKEN_PASSWORD="$(openssl rand -hex 24)"
TOKEN_SUBJECT=alice
DB_PASSWORD="$(openssl rand -hex 24)"
export JWT_PRIVATE_KEY TOKEN_USERNAME TOKEN_PASSWORD TOKEN_SUBJECT DB_PASSWORD
export COMPOSE_PROJECT_NAME="banking-smoke-$$"
compose=(docker compose -f docker/docker-compose.infra.yml)
# shellcheck disable=SC2329 # Invoked by the EXIT trap.
cleanup() { "${compose[@]}" down -v >/dev/null 2>&1 || true; rm -f local-data/demo.key; }
trap cleanup EXIT
"${compose[@]}" up -d --wait db
DB_URL=jdbc:postgresql://db:5432/banking DB_USERNAME=banking_test \
  DOCKER_NETWORK="${COMPOSE_PROJECT_NAME}_default" SEED_SYNTHETIC=true scripts/migrate.sh
"${compose[@]}" up -d
port=$("${compose[@]}" port api 8080 | awk -F: '{print $NF}')
collector_port=$("${compose[@]}" port collector 8080 | awk -F: '{print $NF}')
for _ in $(seq 1 90); do curl -fsS "http://127.0.0.1:$port/readyz" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:$port/readyz" >/dev/null
export API_URL="http://127.0.0.1:$port"
uv run --no-project python scripts/smoke.py
"${compose[@]}" restart api
port=$("${compose[@]}" port api 8080 | awk -F: '{print $NF}')
export API_URL="http://127.0.0.1:$port"
for _ in $(seq 1 90); do curl -fsS "$API_URL/readyz" >/dev/null 2>&1 && break; sleep 1; done
# Restart Alloy to rejoin the same api container network namespace if necessary.
"${compose[@]}" restart alloy
curl -fsS "$API_URL/readyz" >/dev/null
uv run --no-project python scripts/smoke.py
"${compose[@]}" stop collector >/dev/null
uv run --no-project python scripts/smoke.py
sleep 5
"${compose[@]}" start collector >/dev/null
collector_port=$("${compose[@]}" port collector 8080 | awk -F: '{print $NF}')
for _ in $(seq 1 90); do
  result=$(curl -fsS "http://127.0.0.1:$collector_port/")
  if uv run --no-project python -c 'import json,sys; d=json.loads(sys.argv[1]); assert all(d.get(p,0)>0 for p in ["/loki/api/v1/push","/api/v1/write","/v1/traces"])' "$result" 2>/dev/null; then
    "${compose[@]}" exec -T api sh -c 'cat /var/log/app/application*.log' > local-data/application.jsonl
    uv run --no-project python - <<'PYTEST'
import json
from pathlib import Path
rows=[json.loads(line) for line in Path('local-data/application.jsonl').read_text().splitlines() if line.strip()]
ops=[r for r in rows if 'banking operation accepted' in r.get('message','')]
assert ops and all(r.get('trace_id') and r.get('span_id') for r in ops), 'Missing log-to-trace correlation'
assert '00000000-0000-0000-0000-000000000001' not in Path('local-data/application.jsonl').read_text(), 'Account data appeared in logs'
print('PASS: JSON log trace/span correlation and account redaction')
PYTEST
    printf '%s\n' 'PASS: persisted API operations, container restart, brief gateway outage/recovery, HTTP, logs, metrics and traces'; exit 0
  fi
  sleep 1
done
printf '%s\n' 'Telemetry did not reach the local authenticated sink' >&2
"${compose[@]}" logs --tail=30 alloy >&2
exit 1
