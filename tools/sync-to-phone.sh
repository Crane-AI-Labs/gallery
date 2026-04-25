#!/usr/bin/env bash
# Fast incremental install to the connected phone.
#
# Usage:
#   tools/sync-to-phone.sh            # build + fastdeploy-install + launch
#   tools/sync-to-phone.sh --watch    # same, but loop on file changes (needs fswatch)
#
# Why fastdeploy: the APK is 3.5 GB because the GGUF/ONNX models are bundled.
# Normal `adb install -r` re-uploads the full APK each time. `--fastdeploy`
# diffs the installed APK block-by-block and ships only the changed parts,
# so after the first install a code-only change is 5–15 seconds instead of
# 2 minutes.
#
# Prereqs on first run:
#   - Phone paired & USB debugging allowed (adb devices lists it)
#   - Phone has a baseline install of this app (so fastdeploy has something to diff)
#   - fswatch installed for --watch mode:  brew install fswatch

set -euo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APP_ID="com.craneailabs.easehealth"
ACTIVITY="$APP_ID/com.google.ai.edge.gallery.MainActivity"

pick_device() {
    if [[ -n "${ADB_DEVICE:-}" ]]; then
        echo "$ADB_DEVICE"; return
    fi
    # Prefer physical phones over emulators unless only an emulator is attached.
    local phones emulators
    phones=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator/ {print $1}')
    emulators=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator/ {print $1}')
    if [[ -n "$phones" ]]; then
        echo "$phones" | head -1
    elif [[ -n "$emulators" ]]; then
        echo "$emulators" | head -1
    else
        echo "!! no adb device" >&2; exit 2
    fi
}

build_and_install() {
    local device="$1"
    local apk="Android/src/app/build/outputs/apk/debug/app-debug.apk"

    echo "==> building (no-daemon to dodge the gradle cache glitch)"
    ( cd Android/src && ./gradlew :app:assembleDebug --console=plain --no-daemon -q )

    echo "==> fastdeploy install to $device"
    local start ts_end
    start=$(date +%s)
    # --fastdeploy requires a previous install of this APK on the device so it
    # can diff. If this is the very first install, fall back to plain install.
    if "$ADB" -s "$device" shell pm path "$APP_ID" >/dev/null 2>&1; then
        "$ADB" -s "$device" install --fastdeploy -r -t "$apk"
    else
        echo "   (no existing install → full upload, one-time)"
        "$ADB" -s "$device" install -r -t "$apk"
    fi
    ts_end=$(date +%s)
    echo "   install took $((ts_end - start))s"

    echo "==> launching"
    "$ADB" -s "$device" shell am start -n "$ACTIVITY" >/dev/null
}

main() {
    local device; device=$(pick_device)
    echo "device: $device"

    if [[ "${1:-}" == "--watch" ]]; then
        command -v fswatch >/dev/null || { echo "brew install fswatch"; exit 2; }
        echo "==> watching Android/src/app/src/main for changes (^C to stop)"
        # Initial build so fastdeploy has a baseline
        build_and_install "$device"
        fswatch -o \
            Android/src/app/src/main/java \
            Android/src/app/src/main/res \
            Android/src/app/src/main/AndroidManifest.xml \
            Android/src/app/src/main/cpp \
            | while read -r _; do
                echo
                echo "==> change detected — rebuilding"
                if build_and_install "$device"; then
                    echo "✓ done"
                else
                    echo "✗ build failed — staying on previous APK"
                fi
            done
    else
        build_and_install "$device"
    fi
}

main "$@"
