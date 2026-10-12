#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
for f in "$LOG_DIR"/node-*.pid; do
  [ -f "$f" ] || continue
  pid="$(cat "$f")"
  kill "$pid" 2>/dev/null && echo "stopped $pid ($(basename "$f" .pid))" || true
  rm -f "$f"
done
