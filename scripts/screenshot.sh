#!/usr/bin/env bash
# Screenshot helper for the PayAlert UI verification pass.
# Usage: ./scripts/screenshot.sh <out.png> [day|night]
# Captures the current emulator screen into screenshots/after/<out.png>
# (or screenshots/after/night/<out.png> when mode=night).
set -euo pipefail
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
PKG=com.vivekray898.payalert
OUT="$1"; MODE="${2:-day}"
DIR="screenshots/after"
[ "$MODE" = "night" ] && DIR="screenshots/after/night"
mkdir -p "$DIR"
if [ "$MODE" = "night" ]; then
    adb shell cmd uimode night yes >/dev/null 2>&1
else
    adb shell cmd uimode night no >/dev/null 2>&1
fi
adb shell am force-stop "$PKG" >/dev/null 2>&1; sleep 1
adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1; sleep 6
adb exec-out screencap -p > "$DIR/$OUT"
ls -l "$DIR/$OUT"