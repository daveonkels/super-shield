#!/usr/bin/env bash
# Draw the SHIELD's interface at native 4K. The SHIELD outputs 4K but draws its interface at 1920x1080 (density
# 320) and upscales it; 3839x2160 at density 640 keeps every layout the same size and draws text, icons and app
# banners at full resolution. 3839, not 3840: the SHIELD refuses a forced 3840 width. Survives reboots.
# Costs memory: on a SHIELD TV Pro 2019 the launcher went from about 92 MB to 186 MB (3 GB in the box).
#
# Usage:  TV_HOST=<shield ip> shield/ui-4k.sh           # 4K interface
#         TV_HOST=<shield ip> shield/ui-4k.sh --undo    # back to the SHIELD's own 1920x1080 at density 320
#
# Undo sets 1920x1080 and 320 rather than `wm size reset`: the SHIELD's 1080p interface is itself an override
# (persist.vendor.sys.display.resolution), so a reset leaves a 4K screen at density 320, everything half size.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the SHIELD IP address}"
export TV_HOST TV_PORT="${TV_PORT:-5555}"
tv() { tools/venv/bin/python -I tools/tv.py "$@" | tr -d '\r'; }

if [ "${1:-}" = "--undo" ]; then
  tv "wm size 1920x1080; wm density 320"
else
  PHYSICAL=$(tv 'wm size' | sed -n 's/^Physical size: //p')
  if [ "$PHYSICAL" != "3840x2160" ]; then
    echo "The SHIELD is outputting ${PHYSICAL:-an unknown size}, not 3840x2160: set Display resolution to 4K first." >&2
    exit 1
  fi
  tv "wm size 3839x2160; wm density 640"
fi
# The density change restarts every activity; give Home a moment before reporting.
sleep 3
tv "wm size; wm density; dumpsys activity activities | grep mResumedActivity"
