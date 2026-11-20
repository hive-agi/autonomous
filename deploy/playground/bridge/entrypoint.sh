#!/bin/sh
# Serve MCP over HTTP from the mounted config (tokens are stored as SHA-256).
exec bb -cp "$(cat /app/classpath)" -m autonomous.cli serve \
  --config "${AUTONOMOUS_CONFIG:-/etc/autonomous/autonomous.edn}" \
  --http "${AUTONOMOUS_HTTP:-0.0.0.0:7891}" "$@"
