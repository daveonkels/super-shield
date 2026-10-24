#!/usr/bin/env bash
# Build Home Controls, install it on the TV and, optionally, push config files into it.
#   TV_HOST=<tv ip> addons/home-panel/install.sh [config.json ...]
# Each file sets only the keys it has, so the token and the entities can live in separate files:
#   backup/home-panel-secret.json  {"url": "http://192.168.1.10:8123", "token": "..."}     (git-ignored folder)
#   work/home-panel-entities.json  {"temperature": "sensor.x", "scenes": [...], "cameras": [...]}
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
FILES=()
for f in "$@"; do
  [ -f "$f" ] || { echo "no such file: $f" >&2; exit 1; }
  FILES+=("$(cd "$(dirname "$f")" && pwd)/$(basename "$f")")
done
cd "$(dirname "$0")"
PY=../../tools/venv/bin/python
PKG=com.onk.homepanel
tv() { "$PY" -I ../../tools/tv.py "$@"; }

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/home-panel.apk
OUT=$(tv 'pm install -r /data/local/tmp/home-panel.apk; rm -f /data/local/tmp/home-panel.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# Config goes in as a file through the app's provider (only the ADB shell may write it), so the token never sits
# on a command line on the TV. The app takes each file into its own storage and deletes it.
STAGE=/data/local/tmp/home-panel-config.json
for f in ${FILES[@]+"${FILES[@]}"}; do
  tv --push "$f" "$STAGE" >/dev/null
  tv "content write --uri content://$PKG.config/config.json < $STAGE; rm -f $STAGE; echo pushed $(basename "$f")"
done
echo "Installed Home Controls."
