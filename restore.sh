#!/usr/bin/env bash
# Undo the SHIELD cleanup: re-enable every package in shield/debloat.txt, give Home back to the stock launcher,
# and put the screensaver, Private DNS and the 1080p interface back (--keep-launcher keeps the interface size). With a local backup/ (taken before the cleanup) the recorded
# values are used; without one, the Android TV defaults (Backdrop, opportunistic DNS).
#
# Usage:  TV_HOST=<shield ip> ./restore.sh                     # network debugging on, port 5555
#         TV_HOST=127.0.0.1 TV_PORT=15555 ./restore.sh         # through tools/relay.py
#         TV_HOST=<shield ip> ./restore.sh --keep-launcher     # re-enable apps, keep the new launcher as Home
#
# One `pm enable` per shell call: chained pm commands can stop silently after the first.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the SHIELD IP address}"
export TV_HOST TV_PORT="${TV_PORT:-5555}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
tv() { "$PY" -I tools/tv.py "$@"; }
LIST=shield/debloat.txt
KEEP_LAUNCHER=""
[ "${1:-}" = "--keep-launcher" ] && KEEP_LAUNCHER=1
STOCK=com.google.android.tvlauncher

PKGS=$(grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}' | grep -E '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' || true)
for p in $PKGS; do
  [ -n "$KEEP_LAUNCHER" ] && [ "$p" = "$STOCK" ] && continue
  printf '%-50s ' "$p"; tv "pm enable --user 0 $p" | tr -d '\r'
done

if [ -z "$KEEP_LAUNCHER" ]; then
  tv "cmd package set-home-activity $STOCK/.MainActivity" | tr -d '\r'
  tv "input keyevent KEYCODE_HOME" >/dev/null
fi

# A value from backup/settings-<ns>.txt, or "" when there is no backup.
recorded() { [ -f "backup/settings-$1.txt" ] && sed -n "s/^$2=//p" "backup/settings-$1.txt" | tr -d '\r' || true; }

MODE=$(recorded global private_dns_mode)
tv "settings put global private_dns_mode ${MODE:-opportunistic}"
SPEC=$(recorded global private_dns_specifier)
if [ -n "$SPEC" ]; then tv "settings put global private_dns_specifier $SPEC"; else tv "settings delete global private_dns_specifier" >/dev/null; fi
SAVER=$(recorded secure screensaver_components)
tv "settings put secure screensaver_components ${SAVER:-com.google.android.backdrop/.Backdrop}"
# The SHIELD's own interface size, in case shield/ui-4k.sh switched it to 4K. Not `wm size reset`: see ui-4k.sh.
[ -z "$KEEP_LAUNCHER" ] && tv "wm size 1920x1080; wm density 320" >/dev/null

echo "--- still disabled on the TV:"
tv "pm list packages -d" | tr -d '\r'
echo "Done. Reboot the SHIELD once (tools/tv.py --reboot) so every re-enabled service starts again."
