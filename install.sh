#!/bin/bash
# Install TinyDisplay into LineageOS/AOSP source tree for building as system apps.
#
# Installs:
#   - TinyDisplay: main priv-app UI/service/HAL renderer
#   - TinyDisplayTouchHelper: preloaded platform-signed android.uid.system helper
#     that reads rear touch input (hyn_ts) and forwards tap/swipe gestures.
#
# Usage:
#   JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug :touchhelper:assembleDebug
#   ./install.sh /path/to/lineageos/source
#
# Then add both packages to PRODUCT_PACKAGES and rebuild the ROM.

set -euo pipefail

LINEAGE_ROOT="${1:-}"

if [ -z "$LINEAGE_ROOT" ]; then
    echo "Usage: ./install.sh /path/to/lineageos/source"
    exit 1
fi

if [ ! -d "$LINEAGE_ROOT/build/make" ] && [ ! -d "$LINEAGE_ROOT/build/core" ]; then
    echo "Error: doesn't look like a LineageOS/AOSP source tree"
    exit 1
fi

MAIN_APK="app/build/outputs/apk/debug/app-debug.apk"
HELPER_APK="touchhelper/build/outputs/apk/debug/touchhelper-debug.apk"

if [ ! -f "$MAIN_APK" ] || [ ! -f "$HELPER_APK" ]; then
    echo "APK(s) not found. Run:"
    echo "  JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug :touchhelper:assembleDebug"
    exit 1
fi

# LieppOS already keeps custom prebuilts under vendor/lieppos/apps.
# Use that location when present to avoid duplicate Soong modules under packages/apps.
if [ -d "$LINEAGE_ROOT/vendor/lieppos/apps" ]; then
    DEST="$LINEAGE_ROOT/vendor/lieppos/apps/TinyDisplay"
else
    DEST="$LINEAGE_ROOT/packages/apps/TinyDisplay"
fi
mkdir -p "$DEST"

echo "=== Copying APKs/daemon sources ==="
cp "$MAIN_APK" "$DEST/TinyDisplay.apk"
cp "$HELPER_APK" "$DEST/TinyDisplayTouchHelper.apk"
cp -r daemon "$DEST/"

echo "=== Creating Android.bp ==="
cat > "$DEST/Android.bp" << 'EOF'
android_app_import {
    name: "TinyDisplay",
    apk: "TinyDisplay.apk",
    privileged: true,
    certificate: "platform",
    dex_preopt: {
        enabled: false,
    },
    required: ["privapp-permissions-tinydisplay.xml"],
}

// Must be preloaded: Android refuses non-preload apps joining android.uid.system.
// This helper reads /dev/input/eventX for the rear hyn_ts 340x340 touch panel and
// sends signature-protected tap/swipe intents to TinyDisplayService.
android_app_import {
    name: "TinyDisplayTouchHelper",
    apk: "TinyDisplayTouchHelper.apk",
    privileged: true,
    certificate: "platform",
    dex_preopt: {
        enabled: false,
    },
}

prebuilt_etc {
    name: "privapp-permissions-tinydisplay.xml",
    src: "privapp-permissions-tinydisplay.xml",
    sub_dir: "permissions",
}

cc_binary {
    name: "TinyDisplayTouchDaemon",
    stem: "tinydisplay_touch_daemon",
    srcs: ["daemon/tinydisplay_touch_daemon.cpp"],
    shared_libs: ["liblog"],
    system_ext_specific: true,
    init_rc: ["daemon/tinydisplay_touch_daemon.rc"],
}
EOF

echo "=== Copying permissions XML ==="
cp privapp-permissions-tinydisplay.xml "$DEST/"

if [ -d "$LINEAGE_ROOT/vendor/lieppos/sepolicy" ]; then
    echo "=== Installing LieppOS SELinux policy ==="
    cat > "$LINEAGE_ROOT/vendor/lieppos/sepolicy/lieppos_tinydisplay_touchhelper.te" << 'EOF'
# Native daemon fallback: app zygote still gets EACCES on /dev/input on this
# build, so an init-started daemon outside the app sandbox grabs hyn_ts and
# forwards gestures through cmd activity.
type tinydisplay_touch_daemon, domain;
type tinydisplay_touch_daemon_exec, system_file_type, exec_type, file_type;
init_daemon_domain(tinydisplay_touch_daemon)
allow tinydisplay_touch_daemon input_device:dir r_dir_perms;
allow tinydisplay_touch_daemon input_device:chr_file r_file_perms;
allow tinydisplay_touch_daemon system_file:file rx_file_perms;
allow tinydisplay_touch_daemon shell_exec:file rx_file_perms;
allow tinydisplay_touch_daemon activity_service:service_manager find;
# ActivityManager/cmd returns status through stdout/stderr pipes inherited from
# the daemon; system_server must be allowed to use those pipe fds.
allow system_server tinydisplay_touch_daemon:fd use;
binder_use(tinydisplay_touch_daemon)
binder_call(tinydisplay_touch_daemon, system_server)
# cmd/activity also triggers a reverse binder interaction from system_server
# toward the caller domain; without this cmd hangs/fails with Failed transaction.
binder_call(system_server, tinydisplay_touch_daemon)
# Keep the app helper allowance too; harmless if it remains blocked by app sandboxing.
allow system_app input_device:chr_file r_file_perms;
EOF
    if ! grep -q 'tinydisplay_touch_daemon' "$LINEAGE_ROOT/vendor/lieppos/sepolicy/file_contexts"; then
        cat >> "$LINEAGE_ROOT/vendor/lieppos/sepolicy/file_contexts" << 'EOF'
/system_ext/bin/tinydisplay_touch_daemon                         u:object_r:tinydisplay_touch_daemon_exec:s0
/system/system_ext/bin/tinydisplay_touch_daemon                  u:object_r:tinydisplay_touch_daemon_exec:s0
EOF
    fi
fi

echo ""
echo "=== Done ==="
echo ""
echo "Files placed in: $DEST"
echo ""
if [ -f "$LINEAGE_ROOT/vendor/lieppos/flavors/personal.mk" ]; then
    echo "LieppOS personal flavor should include:"
    echo "  PRODUCT_PACKAGES += TinyDisplay TinyDisplayTouchHelper TinyDisplayTouchDaemon"
    echo "File: $LINEAGE_ROOT/vendor/lieppos/flavors/personal.mk"
else
    echo "Add both packages to your device .mk file, e.g.:"
    echo ""
    echo '  PRODUCT_PACKAGES += \'
    echo '      TinyDisplay \'
    echo '      TinyDisplayTouchHelper \\'
echo '      TinyDisplayTouchDaemon'
fi

echo ""
echo "Then rebuild/flash: brunch <device> or mka bacon"
