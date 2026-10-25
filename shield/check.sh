#!/usr/bin/env bash
# After a SHIELD Experience update (or any time): check nothing drifted back. Read-only; it changes nothing.
# Reports packages from shield/debloat.txt that are enabled again, whether this launcher still answers Home,
# whether the Settings-key helper is on, the screensaver, and the display mode. Exits 1 if anything drifted,
# with the command that puts it back.
#
# Usage:  TV_HOST=<shield ip> shield/check.sh
#         TV_HOST=127.0.0.1 TV_PORT=15555 shield/check.sh      # through tools/relay.py
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the SHIELD IP address}"
export TV_HOST TV_PORT="${TV_PORT:-5555}"
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
tv() { "$PY" -I tools/tv.py "$@" | tr -d '\r'; }
LAUNCHER=com.gathin.tvhome
DRIFT=0
bad() { echo "DRIFT  $1"; [ -n "${2:-}" ] && echo "       fix: $2"; DRIFT=1; }
ok() { echo "ok     $1"; }

echo "firmware $(tv 'getprop ro.build.version.incremental')"

# Packages: a listed package the TV has but doesn't report as disabled was turned back on.
WANT=$(grep -vE '^\s*(#|$)' shield/debloat.txt | awk '{print $1}')
ALL=$(tv 'pm list packages' | sed 's/^package://')
DIS=$(tv 'pm list packages -d' | sed 's/^package://')
BACK=""
for p in $WANT; do
  printf '%s\n' "$ALL" | grep -qxF "$p" || continue
  printf '%s\n' "$DIS" | grep -qxF "$p" || BACK="$BACK $p"
done
if [ -n "$BACK" ]; then
  for p in $BACK; do bad "enabled again: $p"; done
  # HANDLERS_ALLOW: Live Channels is an input handler, and a SHIELD has no inputs for it to handle.
  echo "       fix: HANDLERS_ALLOW=1 TV_HOST=$TV_HOST TV_PORT=$TV_PORT shield/apply-batch.sh shield/debloat.txt"
else
  ok "all $(printf '%s\n' "$WANT" | wc -l | tr -d ' ') listed packages disabled (or not on this firmware)"
fi

# Home: Android forgets the chosen Home whenever a launcher is enabled or disabled, and on the SHIELD an
# enabled stock launcher wins over set-home-activity. build-and-install.sh hands Home over with its guard.
HOME_PKG=$(tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1 | cut -d/ -f1)
if [ "$HOME_PKG" = "$LAUNCHER" ]; then ok "Home is $LAUNCHER"; else
  bad "Home is ${HOME_PKG:-unknown}, not $LAUNCHER" "TV_HOST=$TV_HOST TV_PORT=$TV_PORT ./build-and-install.sh"
fi

# The remote's ≡ button: Android switches the helper off on every reinstall or app update.
case "$(tv 'settings get secure enabled_accessibility_services')" in
  *"$LAUNCHER"*) ok "Settings-key helper on (≡ opens quick settings)" ;;
  *) bad "Settings-key helper off: ≡ opens Android's settings" "TV_HOST=$TV_HOST TV_PORT=$TV_PORT ./build-and-install.sh" ;;
esac

SAVER=$(tv 'settings get secure screensaver_components')
case "$SAVER" in
  "$LAUNCHER"/*) ok "screensaver $SAVER" ;;
  *) bad "screensaver is $SAVER" "TV_HOST=$TV_HOST TV_PORT=$TV_PORT tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components $LAUNCHER/.ClockDream'" ;;
esac

# Display: informational, since the 4K interface is optional (shield/ui-4k.sh).
echo "info   display $(tv 'wm size' | tail -n 1 | sed 's/.*: //'), density $(tv 'wm density' | tail -n 1 | sed 's/.*: //')"

[ "$DRIFT" -eq 0 ] && echo "No drift." || echo "Something drifted; run the fix lines above."
exit "$DRIFT"
