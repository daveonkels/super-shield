#!/usr/bin/env bash
# Rebuild the launcher from launcher/ (after editing launcher/app/src/main/res/values/config.xml) and install it
# on the SHIELD as Home, with the Settings-key helper on (the remote's ≡ button opens the quick settings panel).
# Wraps launcher/build-and-install.sh with Homebrew's toolchain paths.
#
# Usage:  TV_HOST=<shield ip> ./build-and-install.sh
#         TV_HOST=127.0.0.1 TV_PORT=15555 ./build-and-install.sh      # through tools/relay.py
#
# Needs: Homebrew openjdk@17 and android-commandlinetools with platforms;android-34 and build-tools;34.0.0,
# and tools/venv (python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt).
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the SHIELD IP address}"
export TV_HOST TV_PORT="${TV_PORT:-5555}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
# Android switches the key helper off on every reinstall; this turns it back on.
export HOME_KEY_SERVICE="${HOME_KEY_SERVICE:-1}"
# Wake the SHIELD first: asleep, its shell can stall long enough for the install script to time out.
tools/venv/bin/python -I tools/tv.py "input keyevent WAKEUP" >/dev/null
launcher/build-and-install.sh
# Keep the launcher's Clock as the screensaver (Backdrop, the stock one, is disabled).
tools/venv/bin/python -I tools/tv.py "settings put secure screensaver_components com.gathin.tvhome/.ClockDream; settings put secure screensaver_enabled 1"
tools/venv/bin/python -I tools/tv.py "dumpsys activity activities | grep mFocusedApp"
