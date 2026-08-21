#!/usr/bin/env bash
set -euo pipefail

controller="${1:-app/src/main/assets/ldfa-x11.sh}"
repository="${2:-.}"
vnc_controller="$repository/app/src/main/assets/ldfa-vnc.sh"

bash -n "$controller"
bash -n "$vnc_controller"

# Termux-side native X11 script is prerequisites/diagnostics only. Android owns process lifecycle.
for pattern in \
  'VERSION="0.8.0"' \
  'DISPLAY_NUMBER=1' \
  '[[ -S "$SOCKET" ]]' \
  '--shared-tmp' \
  '/usr/bin/xset q' \
  'cmd_prepare()' \
  'cmd_probe()' \
  'cmd_heartbeat()' \
  'dump_failure()'; do
  grep -Fq -- "$pattern" "$controller"
done
for forbidden in \
  '/system/bin/app_process' \
  'com.termux.x11.Loader' \
  'start-foreground-service' \
  'startservice' \
  'am start' \
  'cmd_start()' \
  'cmd_stop()'; do
  ! grep -Fq -- "$forbidden" "$controller"
done

service="$repository/app/src/main/java/com/hatake716/linuxdesktop/x11/EmbeddedX11ServerService.kt"
lifecycle="$repository/app/src/main/java/com/hatake716/linuxdesktop/x11/EmbeddedX11ServiceController.kt"
prereq="$repository/app/src/main/java/com/hatake716/linuxdesktop/x11/EmbeddedX11PrerequisiteController.kt"
repository_source="$repository/app/src/main/java/com/hatake716/linuxdesktop/data/LinuxDesktopRepository.kt"
host_compat="$repository/app/src/main/java/com/hatake716/linuxdesktop/data/HostScriptCompatibility.kt"

test -f "$service" -a -f "$lifecycle" -a -f "$prereq" -a -f "$repository_source" -a -f "$host_compat"
grep -Fq -- 'class EmbeddedX11ServerService : Service()' "$service"
grep -Fq -- 'ICmdEntryInterface.Stub()' "$service"
grep -Fq -- 'EmbeddedX11ServerBridge.start(args)' "$service"
grep -Fq -- 'intent?.action != ACTION_START' "$service"
grep -Fq -- 'return START_NOT_STICKY' "$service"
grep -Fq -- 'Process.killProcess(Process.myPid())' "$service"
! grep -Fq -- 'return START_STICKY' "$service"
! grep -Fq -- 'ServerSocket' "$service"
! grep -Fq -- '7892' "$service"
! grep -Fq -- 'sendBroadcast' "$service"
! grep -Fq -- 'CmdEntryPoint.ACTION_START' "$service"
! grep -Fq -- 'ActivityThread' "$service"
! grep -Fq -- 'Unsafe' "$service"

grep -Fq -- 'EmbeddedX11PrerequisiteController.ensure(context)' "$lifecycle"
grep -Fq -- 'ContextCompat.startForegroundService(context, intent)' "$lifecycle"
grep -Fq -- 'context.stopService' "$lifecycle"
grep -Fq -- 'START_STABLE_POLLS' "$lifecycle"
grep -Fq -- 'isLockOwnerAlive()' "$lifecycle"
grep -Fq -- 'OsConstants.S_ISSOCK' "$lifecycle"
grep -Fq -- 'bindService(' "$lifecycle"
grep -Fq -- 'EmbeddedX11Display.connect(appContext, service)' "$lifecycle"
grep -Fq -- 'X11_PROCESS_NAME = "com.termux:x11"' "$lifecycle"
grep -Fq -- 'File("/proc/$pid/cmdline")' "$lifecycle"
grep -Fq -- 'OsConstants.SIGTERM' "$lifecycle"
grep -Fq -- 'OsConstants.SIGKILL' "$lifecycle"
grep -Fq -- 'cleanupEndpointsAfterVerifiedExit' "$lifecycle"
! grep -Fq -- '/system/bin/am' "$lifecycle"

grep -Fq -- 'object EmbeddedX11PrerequisiteController' "$prereq"
grep -Fq -- 'context.assets.open("ldfa-x11.sh")' "$prereq"
grep -Fq -- 'runBundledX11Script' "$prereq"
grep -Fq -- 'action = "prepare"' "$prereq"

grep -Fq -- 'EmbeddedX11ServiceController.restartAndWait' "$repository_source"
grep -Fq -- 'EmbeddedX11ServiceController.stopAndWait' "$repository_source"
grep -Fq -- 'EmbeddedX11ServiceController.openDisplay(context)' "$repository_source"
grep -Fq -- 'NATIVE_X11_RENDER_MODES' "$repository_source"
grep -Fq -- 'NATIVE_X11_MODE_LEGACY' "$repository_source"
grep -Fq -- 'EmbeddedX11Display.isOpen()' "$repository_source"
! grep -Fq -- 'runInstalledX11("stop"' "$repository_source"

# Host worker must follow whichever backend is active: native :1 or compatibility :2.
grep -Fq -- 'CURRENT_VERSION = "VERSION=\"0.5.0\""' "$host_compat"
grep -Fq -- 'DEFAULT_DISPLAY_NUMBER=1' "$host_compat"
grep -Fq -- 'detect_active_display()' "$host_compat"
grep -Fq -- '.X11-unix/X2' "$host_compat"
grep -Fq -- 'LDFA_DISPLAY_NUMBER=' "$host_compat"
grep -Fq -- 'upgradeLegacyStartWorkerCalls' "$host_compat"
grep -Fq -- 'display preflight xset failed' "$host_compat"

app_manifest="$repository/app/src/main/AndroidManifest.xml"
grep -Fq -- '.x11.EmbeddedX11ServerService' "$app_manifest"
grep -Fq -- 'android:process=":x11"' "$app_manifest"
! grep -Fq -- 'android:extractNativeLibs=' "$app_manifest"

embedded_manifest="$repository/embedded-x11/src/main/AndroidManifest.xml"
! grep -Fq -- 'com.termux.x11.CmdEntryPoint.ACTION_START' "$embedded_manifest"

bridge_java="$repository/embedded-x11/src/main/java/com/termux/x11/EmbeddedX11ServerBridge.java"
bridge_native="$repository/embedded-x11/src/main/cpp/embedded_server.cpp"
grep -Fq -- 'System.loadLibrary("Xlorie")' "$bridge_java"
grep -Fq -- 'native boolean start(String[] args)' "$bridge_java"
grep -Fq -- 'Java_com_termux_x11_CmdEntryPoint_start' "$bridge_native"
grep -Fq -- 'Java_com_termux_x11_EmbeddedX11ServerBridge_getXConnection' "$bridge_native"

native_patch="$repository/embedded-x11/scripts/prepare_embedded_native.py"
native_wrapper="$repository/embedded-x11/src/main/cpp/CMakeLists.txt"
grep -Fq -- 'CPU_ZERO(&mask)' "$native_patch"
grep -Fq -- 'Termux:X11 LD_PRELOAD block changed' "$native_patch"
grep -Fq -- 'unsetenv(\"LD_PRELOAD\")' "$native_patch"
grep -Fq -- 'AChoreographer unavailable during X server startup' "$native_patch"
grep -Fq -- 'return JNI_FALSE' "$native_patch"
grep -Fq -- 'pthread_mutex_lock(&resources->renderer.stateLock)' "$native_patch"
grep -Fq -- 'embedded_server.cpp' "$native_wrapper"

hardened_tmp="$(mktemp -d)"
trap 'rm -rf "$hardened_tmp"' EXIT
python3 "$native_patch" "$repository/vendor/termux-x11/lorie/src/main/cpp" "$hardened_tmp"
grep -Fq -- 'CPU_ZERO(&mask)' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'unsetenv("LD_PRELOAD")' "$hardened_tmp/cmdentrypoint.cpp"
! grep -Fq -- 'setenv("LD_PRELOAD", "/data/data/com.termux/files/usr/lib/libtermux-exec.so"' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'if (!choreographer)' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'return JNI_FALSE' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'Java_com_termux_x11_EmbeddedX11Display_nativeRenderedFrames' "$hardened_tmp/activity.cpp"
grep -Fq -- 'pthread_mutex_lock(&resources->renderer.stateLock)' "$hardened_tmp/activity.cpp"
rm -rf "$hardened_tmp"
trap - EXIT

display="$repository/embedded-x11/src/main/java/com/termux/x11/EmbeddedX11Display.java"
grep -Fq -- 'public static void connect(Context context, IBinder serviceBinder)' "$display"
grep -Fq -- 'activity.onReceiveConnection' "$display"
grep -Fq -- 'public static boolean isOpen()' "$display"
grep -Fq -- 'public static boolean isTransportConnected()' "$display"
grep -Fq -- 'isSurfaceReady()' "$display"
grep -Fq -- 'renderedFrames() > 0' "$display"
grep -Fq -- 'activity.finishAffinity()' "$display"

settings="$repository/settings.gradle.kts"
app_build="$repository/app/build.gradle.kts"
! grep -Fq -- 'include(":embedded-x11-loader")' "$settings"
! grep -Fq -- 'embedded-x11-loader' "$app_build"
! grep -Fq -- 'x11-loader-assets' "$app_build"
grep -Fq -- 'versionName = "0.8.0+x11"' "$app_build"
grep -Fq -- 'HOST_SCRIPT_VERSION", "\"0.5.0\""' "$app_build"

# Compatibility display is intentionally isolated from native X1.
for pattern in \
  'VERSION="0.8.0"' \
  'DISPLAY_NUMBER=2' \
  'VNC_PORT=5902' \
  '[[ -S "$SOCKET" ]]' \
  'activate_host_display()' \
  'ldfa-run-$id' \
  'tigervnc-standalone-server' \
  'Xtigervnc' \
  'novnc' \
  'websockify' \
  '-nolisten tcp' \
  '--shared-tmp' \
  '/usr/bin/xset q'; do
  grep -Fq -- "$pattern" "$vnc_controller"
done
! grep -Fq -- 'DISPLAY_NUMBER=1' "$vnc_controller"
! grep -Fq -- '/tmp/.X1-lock' "$vnc_controller"
! grep -Fq -- '/tmp/.X11-unix/X1' "$vnc_controller"

echo "v0.8 direct-Binder X11 architecture checks passed"
