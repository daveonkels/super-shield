#!/usr/bin/env bash
# Apply one batch list with tools/disable.sh (Home, input and core-package guards included), then show which
# of its packages the TV now reports as disabled.
#   TV_HOST=<shield ip> shield/apply-batch.sh shield/batches/batch-1-telemetry.txt
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the SHIELD IP address}"
LIST="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
cd "$(dirname "$0")/.."
tools/disable.sh "$LIST"
DIS=$(tools/venv/bin/python -I tools/tv.py "pm list packages -d" | tr -d '\r' | sed 's/^package://')
for p in $(grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}'); do
  if printf '%s\n' "$DIS" | grep -qxF "$p"; then echo "disabled: $p"; else echo "NOT disabled: $p"; fi
done
