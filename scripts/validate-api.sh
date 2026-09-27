#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
uv run --no-project --with openapi-spec-validator==0.7.2 python -m openapi_spec_validator spec/openapi.yaml
