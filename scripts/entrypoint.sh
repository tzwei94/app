#!/bin/sh
set -eu
umask 077
case "${1:-}" in
  migrate|rollback|clear-checksums|clearchecksum|bootstrap)
    exec java -jar /app/app.jar "$@"
    ;;
esac
mkdir -p "$LOG_PATH"
exec java -jar /app/app.jar "$@"
