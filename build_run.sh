#!/usr/bin/env bash
# build_run.sh — Clean, build (debug), and install/launch on connected device

set -euo pipefail

APP_ID="com.zebra.rfid.demo.sdksample"
ACTIVITY="${APP_ID}/com.zebra.rfid.demo.sdksample.MainActivity"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR"

# ── 1. Clean ──────────────────────────────────────────────────────────────────
echo ">>> Cleaning..."
./gradlew clean

# ── 2. Build debug APK ───────────────────────────────────────────────────────
echo ">>> Building debug APK..."
./gradlew assembleDebug

# ── 3. Verify a device/emulator is connected ─────────────────────────────────
DEVICE_COUNT=$(adb devices | tail -n +2 | grep -c "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "ERROR: No ADB device connected. Connect a device and retry."
    exit 1
fi

# ── 4. Install APK ───────────────────────────────────────────────────────────
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
echo ">>> Installing ${APK_PATH}..."
adb install -r "$APK_PATH"

# ── 5. Launch app ─────────────────────────────────────────────────────────────
echo ">>> Launching ${ACTIVITY}..."
adb shell am start -n "$ACTIVITY"

echo ">>> Done."
