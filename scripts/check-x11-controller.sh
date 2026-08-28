#!/usr/bin/env bash
set -euo pipefail

controller="${1:-app/src/main/assets/ldfa-x11.sh}"
repository="${2:-.}"
bash -n "$controller"

# Termux-side native X11 script is prerequisites/diagnostics only. Android owns process lifecycle.
for pattern in \
  'VERSION="1.1.0"' \
  'DISPLAY_NUMBER=1' \
  '[[ -S "$SOCKET" ]]' \
  '--shared-tmp' \
  '/usr/bin/xset q' \
  'cmd_prepare()' \
  'cmd_probe()' \
  'cmd_draw_probe()' \
  '/usr/bin/xrefresh -solid' \
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
keep_alive="$repository/app/src/main/java/com/hatake716/linuxdesktop/service/DesktopKeepAliveService.kt"

test -f "$service" -a -f "$lifecycle" -a -f "$prereq" -a -f "$repository_source" -a -f "$host_compat" -a -f "$keep_alive"
grep -Fq -- 'class EmbeddedX11ServerService : Service()' "$service"
grep -Fq -- 'ICmdEntryInterface.Stub()' "$service"
grep -Fq -- 'EmbeddedX11ServerBridge.start(args)' "$service"
grep -Fq -- 'add("-noreset")' "$service"
grep -Fq -- 'intent?.action != ACTION_START' "$service"
grep -Fq -- 'return START_NOT_STICKY' "$service"
grep -Fq -- 'Process.killProcess(Process.myPid())' "$service"
grep -Fq -- 'writeServiceState(requestedGeneration)' "$service"
grep -Fq -- 'EXTRA_GENERATION' "$service"
grep -Fq -- 'SERVICE_STATE_FILE' "$service"
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
grep -Fq -- 'EmbeddedX11Display.connect(' "$lifecycle"
grep -Fq -- 'serviceGeneration,' "$lifecycle"
# Renamed app derives the :x11 process name; and ownership checks must NOT
# read /proc of sibling processes — hidepid=invisible hides them on RELEASE
# builds (only debuggable builds hold the readproc exemption). kill(pid, 0)
# is the hidepid-safe liveness/ownership probe.
grep -Fq -- 'X11_PROCESS_NAME = "${BuildConfig.APPLICATION_ID}:x11"' "$lifecycle"
! grep -Fq -- 'File("/proc/$pid/cmdline")' "$lifecycle"
grep -Fq -- 'Os.kill(pid, 0)' "$lifecycle"
grep -Fq -- 'OsConstants.SIGTERM' "$lifecycle"
grep -Fq -- 'OsConstants.SIGKILL' "$lifecycle"
grep -Fq -- 'cleanupEndpointsAfterVerifiedExit' "$lifecycle"
grep -Fq -- 'isExpectedServiceReady(context, generation)' "$lifecycle"
grep -Fq -- 'fun isServiceReady(context: Context)' "$lifecycle"
grep -Fq -- 'pendingDisplayBinds.containsKey(this)' "$lifecycle"
grep -Fq -- 'displayOpenAllowed' "$lifecycle"
grep -Fq -- 'catch (launchFailure: RuntimeException)' "$lifecycle"
grep -Fq -- 'displayOpenFailure = launchFailure' "$lifecycle"
grep -Fq -- 'EmbeddedX11Display.close(appContext)' "$lifecycle"
grep -Fq -- 'fun hasDisplayOpenFailure()' "$lifecycle"
grep -Fq -- 'fun consumeDisplayOpenFailure()' "$lifecycle"
grep -Fq -- 'pendingDisplayBinds.clear()' "$lifecycle"
grep -Fq -- 'fun cancelPendingDisplayOpen()' "$lifecycle"
grep -Fq -- 'cleanupServiceStateAfterVerifiedExit' "$lifecycle"
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
grep -Fq -- 'EmbeddedX11ServiceController.hasDisplayOpenFailure()' "$repository_source"
grep -Fq -- 'EmbeddedX11ServiceController.consumeDisplayOpenFailure()' "$repository_source"
grep -Fq -- 'verifyNativeDesktopPresentation(id)' "$repository_source"
grep -Fq -- 'startAndProbeHost(id)' "$repository_source"
grep -Fq -- 'if (id != null && currentId != id) return@withLock currentId != null' "$repository_source"
grep -Fq -- 'activeContainerId() != effectiveId' "$repository_source"
grep -Fq -- 'if (nativeFailure is CancellationException) throw nativeFailure' "$repository_source"
grep -Fq -- 'if (recoveryFailure is CancellationException)' "$repository_source"
grep -Fq -- 'holdTermuxServiceLifetime()' "$repository_source"
grep -Fq -- 'Intent(context, TermuxService::class.java)' "$repository_source"
grep -Fq -- 'Context.BIND_AUTO_CREATE' "$repository_source"
grep -Fq -- 'releaseTermuxServiceLifetime()' "$repository_source"
grep -Fq -- 'display cleanup failed; retaining Termux service lease' "$repository_source"
! grep -Fq -- 'runInstalledX11("stop"' "$repository_source"

# An old notification must not stop the foreground monitor after a newer
# container became active while the stop command was waiting on lifecycle IO.
grep -Fq -- 'catch (cancelled: CancellationException)' "$keep_alive"
grep -Fq -- 'throw cancelled' "$keep_alive"
grep -Fq -- 'private fun stopActiveSession(requestedId: String?, stopStartId: Int)' "$keep_alive"
grep -Fq -- 'withContext(Dispatchers.Main.immediate)' "$keep_alive"
grep -Fq -- 'val remainingId = repository.activeContainerId()' "$keep_alive"
grep -Fq -- 'startMonitoring(remainingId)' "$keep_alive"
grep -Fq -- 'stopSelfResult(stopStartId)' "$keep_alive"
! grep -Fq -- 'runCatching { repository.stopContainer(id) }' "$keep_alive"
python3 - "$keep_alive" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
block = text.split("private fun stopActiveSession", 1)[1].split("private fun buildNotification", 1)[0]
assert "stopSelf()" not in block
assert block.index("val remainingId = repository.activeContainerId()") < block.index("stopSelfResult(stopStartId)")
assert block.index("stopSelfResult(stopStartId)") < block.index("stopForeground(STOP_FOREGROUND_REMOVE)")
PY

# Host worker must follow whichever backend is active: native :1 or compatibility :2.
grep -Fq -- 'CURRENT_VERSION = "VERSION=\"1.1.0\""' "$host_compat"
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
grep -Fq -- 'expected one match, found' "$native_patch"
grep -Fq -- 'unsetenv("LD_PRELOAD")' "$native_patch"
grep -Fq -- 'AChoreographer unavailable during X server startup' "$native_patch"
grep -Fq -- 'return JNI_FALSE' "$native_patch"
grep -Fq -- 'ldfaSuccessfulPresentSerial' "$native_patch"
grep -Fq -- 'pthread_mutex_lock(&stateLock)' "$native_patch"
grep -Fq -- 'LDFA_RENDERER_FAILED' "$native_patch"
grep -Fq -- 'pthread_join(thread, nullptr)' "$native_patch"
grep -Fq -- 'pthread_cond_timedwait' "$native_patch"
grep -Fq -- 'LDFA_GPU_FENCE_TIMEOUT_NS = 1000000000ULL' "$native_patch"
grep -Fq -- 'clock_gettime(CLOCK_REALTIME' "$native_patch"
grep -Fq -- 'std::atomic_bool stopping{false}' "$native_patch"
grep -Fq -- 'EVENT_FULL_REDRAW' "$native_patch"
grep -Fq -- 'DamageDamageRegion(&root->drawable, &region)' "$native_patch"
grep -Fq -- 'lorie_mutex_lock_interruptible' "$native_patch"
grep -Fq -- 'stopping.store(true, std::memory_order_release)' "$native_patch"
grep -Fq -- 'attempt < 20 && !buf && !stopping.load(std::memory_order_acquire)' "$native_patch"
grep -Fq -- 'ldfaViewerConnectionAlive' "$native_patch"
grep -Fq -- 'SOCK_STREAM | SOCK_CLOEXEC' "$native_patch"
grep -Fq -- 'MSG_DONTWAIT | MSG_NOSIGNAL' "$native_patch"
grep -Fq -- 'conn_fd == fd' "$native_patch"
grep -Fq -- 'find_program(LDFA_HOST_C_COMPILER NAMES cc gcc REQUIRED)' "$native_patch"
grep -Fq -- 'embedded_server.cpp' "$native_wrapper"
grep -Fq -- 'upstream-cpp' "$native_wrapper"

hardened_tmp="$(mktemp -d)"
trap 'rm -rf "$hardened_tmp"' EXIT
python3 "$native_patch" "$repository/vendor/termux-x11/lorie/src/main/cpp" "$hardened_tmp"
grep -Fq -- 'CPU_ZERO(&mask)' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'unsetenv("LD_PRELOAD")' "$hardened_tmp/cmdentrypoint.cpp"
! grep -Fq -- 'setenv("LD_PRELOAD", "/data/data/com.termux/files/usr/lib/libtermux-exec.so"' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'if (!choreographer)' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'return JNI_FALSE' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'Java_com_termux_x11_EmbeddedX11Display_nativeSuccessfulPresentSerial' "$hardened_tmp/activity.cpp"
grep -Fq -- 'eglSwapBuffers(egl_display, sfc)' "$hardened_tmp/renderer.cpp"
grep -Fq -- '__atomic_add_fetch(&ldfaSuccessfulPresentSerial' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'void Renderer::threadLoop()' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'pthread_mutex_lock(&stateLock)' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'pthread_cond_timedwait' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'LDFA_GPU_FENCE_TIMEOUT_NS = 1000000000ULL' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'std::atomic_bool stopping{false}' "$hardened_tmp/lorie.h"
grep -Fq -- 'EVENT_FULL_REDRAW' "$hardened_tmp/lorie.h"
grep -Fq -- '#include <pixmapstr.h>' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'case EVENT_FULL_REDRAW:' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'PixmapRegionInit(&region, root)' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'DamageDamageRegion(&root->drawable, &region)' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- '{"requestFullRedraw", "(J)V"' "$hardened_tmp/activity.cpp"
grep -Fq -- 'bool lorie_mutex_lock_interruptible(' "$hardened_tmp/lorie.h"
test "$(grep -Fc -- 'lorie_mutex_lock_interruptible(' "$hardened_tmp/renderer.cpp")" -eq 3
grep -Fq -- 'stopping.store(true, std::memory_order_release)' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'stateDeadline.tv_sec += 1' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'windowDeadline.tv_sec += 1' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'attempt < 20 && !buf && !stopping.load(std::memory_order_acquire)' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'Do not begin another potentially blocking vendor EGL call' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'do not enter the second fence wait' "$hardened_tmp/renderer.cpp"
! grep -Fq -- 'stateDeadline.tv_sec += 5' "$hardened_tmp/renderer.cpp"
! grep -Fq -- 'windowDeadline.tv_sec += 5' "$hardened_tmp/renderer.cpp"
grep -Fq -- 'clock_gettime(CLOCK_REALTIME' "$hardened_tmp/lorie.h"
grep -Fq -- 'ownerAlive || lorieConnectionAlive()' "$hardened_tmp/lorie.h"
grep -Fq -- 'return serverAlive || ldfaViewerConnectionAlive()' "$hardened_tmp/cmdentrypoint.cpp"
grep -Fq -- 'extern "C" bool ldfaViewerConnectionAlive(void)' "$hardened_tmp/activity.cpp"
grep -Fq -- '+[](JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text)' "$hardened_tmp/activity.cpp"
grep -Fq -- '+[](JNIEnv* env, __unused jobject cls, jlong ptr, jfloat x, jfloat y' "$hardened_tmp/activity.cpp"
grep -Fq -- '+[](JNIEnv *env, __unused jobject thiz, jlong ptr, jfloat x, jfloat y, jint pressure' "$hardened_tmp/activity.cpp"
! grep -Fq -- '+[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text)' "$hardened_tmp/activity.cpp"
grep -Fq -- 'MSG_DONTWAIT | MSG_NOSIGNAL' "$hardened_tmp/renderer.cpp"
! grep -Fq -- 'ANativeWindow_acquire(newWin)' "$hardened_tmp/renderer.cpp"
! grep -Fq -- 'abort();' "$hardened_tmp/renderer.cpp"
python3 - "$hardened_tmp/renderer.cpp" "$hardened_tmp/lorie.h" <<'PY'
from pathlib import Path
import sys

renderer = Path(sys.argv[1]).read_text(encoding="utf-8")
header = Path(sys.argv[2]).read_text(encoding="utf-8")

destroy = renderer.split("void Renderer::destroy() {", 1)[1].split("\n}\n", 1)[0]
assert destroy.index("stopping.store(true, std::memory_order_release)") < destroy.index("pthread_mutex_lock(&stateLock)")

acquired_cancel = """__atomic_store_n(lockingPid, getpid(), __ATOMIC_RELEASE);
            if (cancelled->load(std::memory_order_acquire)) {
                __atomic_store_n(lockingPid, 0, __ATOMIC_RELEASE);
                pthread_mutex_unlock(mutex);
                return false;"""
assert acquired_cancel in header

cursor_cleanup = """if (!lorie_mutex_lock_interruptible(
                &state->cursor.lock, &state->cursor.lockingPid, &stopping)) {
            if (fence != EGL_NO_SYNC_KHR)
                eglDestroySyncKHR(egl_display, fence);
            lorie_mutex_unlock(&state->lock, &state->lockingPid);
            return;"""
assert cursor_cleanup in renderer

root_unlock = renderer.index("state->waitForNextFrame = true;")
pre_swap_guard = renderer.index("if (stopping.load(std::memory_order_acquire))", root_unlock)
swap = renderer.index("EGLBoolean presented = eglSwapBuffers", root_unlock)
assert root_unlock < pre_swap_guard < swap

post_swap_guard = renderer.index("if (stopping.load(std::memory_order_acquire))", swap)
prequeue = renderer.index("fence = eglCreateSyncKHR", post_swap_guard)
assert swap < post_swap_guard < prequeue
PY
grep -Fq -- 'find_program(LDFA_HOST_C_COMPILER NAMES cc gcc REQUIRED)' \
  "$hardened_tmp/upstream-cpp/recipes/xkbcomp.cmake"
! grep -Fq -- '/usr/bin/gcc' "$hardened_tmp/upstream-cpp/recipes/xkbcomp.cmake"
rm -rf "$hardened_tmp"
trap - EXIT

java_patch="$repository/embedded-x11/scripts/prepare_embedded_java.py"
java_tmp="$(mktemp -d)"
trap 'rm -rf "$java_tmp"' EXIT
python3 "$java_patch" "$repository/vendor/termux-x11/lorie/src/main/java" "$java_tmp"
generated_lorie="$java_tmp/com/termux/x11/LorieView.java"
generated_activity="$java_tmp/com/termux/x11/MainActivity.java"
generated_input="$java_tmp/com/termux/x11/input/TouchInputHandler.java"
! grep -Fq -- 'CriticalNative' "$generated_lorie"
! grep -Fq -- 'FastNative' "$generated_lorie"
grep -Fq -- 'onReceiveConnection(getIntent());' "$generated_activity"
grep -Fq -- 'if (serviceBinder != binder)' "$generated_activity"
grep -Fq -- 'handler.removeCallbacks(connectRetry)' "$generated_activity"
grep -Fq -- 'void shutdownNative()' "$generated_lorie"
grep -Fq -- 'private boolean mNativeShutdown' "$generated_lorie"
grep -Fq -- 'private final Runnable mDeferredFullRedraw' "$generated_lorie"
grep -Fq -- 'removeCallbacks(mDeferredFullRedraw)' "$generated_lorie"
grep -Fq -- 'postDelayed(mDeferredFullRedraw, 400)' "$generated_lorie"
grep -Fq -- 'private native void requestFullRedraw(long ptr)' "$generated_lorie"
grep -Fq -- 'view.requestFullRedraw()' "$generated_activity"
grep -Fq -- 'getLorieView().requestFullRedraw()' "$generated_activity"
grep -Fq -- 'MainActivity activity = MainActivity.getInstance();' "$generated_lorie"
grep -Fq -- 'activity != null && activity.useTermuxEKBarBehaviour' "$generated_lorie"
! grep -Fq -- 'private final MainActivity a = MainActivity.getInstance();' "$generated_lorie"
! grep -Fq -- 'a.useTermuxEKBarBehaviour' "$generated_lorie"
grep -Fq -- 'view.shutdownNative()' "$generated_activity"
grep -Fq -- 'EmbeddedX11Display.isLaunchIntentAllowed(getIntent())' "$generated_activity"
grep -Fq -- 'EmbeddedX11Display.viewerDestroyed(getIntent())' "$generated_activity"
grep -Fq -- 'catch (NoSuchElementException ignored)' "$generated_activity"
grep -Fq -- 'private static final String LDFA_IME_RESIZE_MIGRATION = "ldfaImeResizeV1"' "$generated_activity"
grep -Fq -- '.putBoolean("Reseed", true)' "$generated_activity"
grep -Fq -- 'LDFA enabled IME-aware X11 resizing' "$generated_activity"
grep -Fq -- 'long getNativeContext()' "$generated_lorie"
! grep -Fq -- 'nativeDestroy(mNativeContext)' "$generated_lorie"
! grep -Fq -- 'postDelayed(this::finishStartupDraw' "$generated_activity"
! grep -Fq -- 'postDelayed(this::onPreferencesChangedCallback' "$generated_activity"
! grep -Fq -- 'getLorieView().requestConnection()' "$generated_activity"
test "$(grep -Fc -- 'refreshInputDevices(mActivity);' "$generated_input")" -eq 4
grep -Fq -- 'refreshInputDevices(MainActivity.getInstance());' "$generated_input"
grep -Fq -- 'private static void refreshInputDevices(MainActivity activity)' "$generated_input"
grep -Fq -- 'LorieView view = activity.getLorieView();' "$generated_input"
! grep -Fq -- 'MainActivity.getInstance().getLorieView()' "$generated_input"
rm -rf "$java_tmp"
trap - EXIT

display="$repository/embedded-x11/src/main/java/com/termux/x11/EmbeddedX11Display.java"
grep -Fq -- 'public static void connect(Context context, IBinder serviceBinder, String generation)' "$display"
grep -Fq -- 'public static void restoreLaunchGeneration(String generation)' "$display"
grep -Fq -- 'allowedLaunchGeneration = null' "$display"
grep -Fq -- 'isLaunchIntentAllowed(Intent intent)' "$display"
grep -Fq -- 'generation != null && generation.equals(allowedLaunchGeneration)' "$display"
grep -Fq -- 'if (generation != null && generation.equals(allowedLaunchGeneration))' "$display"
grep -Fq -- 'activity.onReceiveConnection' "$display"
grep -Fq -- 'public static boolean isOpen()' "$display"
grep -Fq -- 'public static boolean isViewerForeground()' "$display"
grep -Fq -- 'activity.hasWindowFocus()' "$display"
grep -Fq -- 'public static boolean isTransportConnected()' "$display"
grep -Fq -- 'isSurfaceReady()' "$display"
grep -Fq -- 'public static boolean isViewerReady()' "$display"
grep -Fq -- 'successfulPresentSerial()' "$display"
grep -Fq -- 'return view.getNativeContext();' "$display"
! grep -Fq -- 'getDeclaredField("mNativeContext")' "$display"
! grep -Fq -- 'renderedFrames() > 0' "$display"
grep -Fq -- 'activity.finishAffinity()' "$display"
grep -Fq -- 'viewerWasOpen && isNativeViewerForeground()' "$repository_source"
grep -Fq -- 'native heartbeat kept Xorg/XFCE alive while viewer is backgrounded' "$repository_source"
grep -Fq -- 'ensureBundledDesktopApps(id)' "$repository_source"
grep -Fq -- 'action = "ensure-apps"' "$repository_source"
python3 - "$repository_source" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text()
start = source.index("suspend fun startContainer")
ensure = source.index("ensureBundledDesktopApps(id)", start)
display = source.index("selectAndStartDisplayBackend(id)", start)
assert start < ensure < display
PY
controller="$repository/app/src/main/java/com/hatake716/linuxdesktop/x11/EmbeddedX11ServiceController.kt"
application="$repository/app/src/main/java/com/hatake716/linuxdesktop/LinuxDesktopApplication.kt"
grep -Fq -- 'fun restoreDisplayAccess(context: Context): Boolean' "$controller"
grep -Fq -- 'if (!isExpectedServiceReady(context, state.generation)) return false' "$controller"
grep -Fq -- 'EmbeddedX11Display.restoreLaunchGeneration(state.generation)' "$controller"
grep -Fq -- 'repository.activeDisplayBackend() == DesktopDisplayBackend.NATIVE_X11' "$application"
grep -Fq -- 'EmbeddedX11ServiceController.restoreDisplayAccess(this)' "$application"
grep -Fq -- 'registerX11ViewerLifecycle()' "$application"
grep -Fq -- 'if (activity !is X11MainActivity) return' "$application"
grep -Fq -- 'scheduleViewerResumeRecovery()' "$application"
grep -Fq -- 'repository.recoverActiveDesktopAfterViewerResume()' "$application"
grep -Fq -- 'EmbeddedX11ServiceController.openDisplay(this@LinuxDesktopApplication)' "$application"
grep -Fq -- 'suspend fun recoverActiveDesktopAfterViewerResume()' "$repository_source"
grep -Fq -- 'desktopResumeProcessState(id)' "$repository_source"
grep -Fq -- 'viewer resume used zero-process fast path' "$repository_source"
grep -Fq -- 'File("/proc").listFiles()' "$repository_source"
grep -Fq -- 'DesktopResumeProcessState.IN_SESSION_REPAIR' "$repository_source"
grep -Fq -- 'RESUME_IN_SESSION_REPAIR_ATTEMPTS' "$repository_source"
grep -Fq -- 'action = "resume"' "$repository_source"
grep -Fq -- 'action = "health"' "$repository_source"
python3 - "$repository_source" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text()
start = source.index("suspend fun recoverActiveDesktopAfterViewerResume")
end = source.index("fun activeContainerId", start)
resume = source[start:end]
assert "runInstalledHost" in resume
assert "runBundledHostScript" not in resume
PY
main_activity="$repository/app/src/main/java/com/hatake716/linuxdesktop/MainActivity.kt"
main_view_model="$repository/app/src/main/java/com/hatake716/linuxdesktop/ui/MainViewModel.kt"
grep -Fq -- 'viewModel.setHostActivityVisible(true)' "$main_activity"
grep -Fq -- 'viewModel.setHostActivityVisible(false)' "$main_activity"
grep -Fq -- 'fun setHostActivityVisible(visible: Boolean)' "$main_view_model"
grep -Fq -- 'containerRefreshJob?.cancel()' "$main_view_model"
# The VNC fallback (VncFallbackActivity / ldfa-vnc.sh) was removed: nothing of
# it may return.
! test -e "$repository/app/src/main/java/com/hatake716/linuxdesktop/display/VncFallbackActivity.kt"
! test -e "$repository/app/src/main/assets/ldfa-vnc.sh"

settings="$repository/settings.gradle.kts"
app_build="$repository/app/build.gradle.kts"
! grep -Fq -- 'include(":embedded-x11-loader")' "$settings"
! grep -Fq -- 'embedded-x11-loader' "$app_build"
! grep -Fq -- 'x11-loader-assets' "$app_build"
grep -Fq -- 'versionName = "1.1.0"' "$app_build"
grep -Fq -- 'HOST_SCRIPT_VERSION", "\"1.1.0\""' "$app_build"

dialogs="$repository/app/src/main/java/com/hatake716/linuxdesktop/ui/Dialogs.kt"
main_view_model="$repository/app/src/main/java/com/hatake716/linuxdesktop/ui/MainViewModel.kt"
settings_screen="$repository/app/src/main/java/com/hatake716/linuxdesktop/ui/SettingsScreen.kt"
process_exit_diagnostics="$repository/app/src/main/java/com/hatake716/linuxdesktop/data/ProcessExitDiagnostics.kt"
grep -Fq -- 'desktopStartInProgress' "$main_view_model"
grep -Fq -- 'デスクトップが表示されるまで少し時間がかかります。' "$dialogs"
! grep -Fq -- 'X11ディスプレイを開く' "$settings_screen"
! grep -Fq -- 'onOpenDisplay' "$settings_screen"
grep -Fq -- 'getHistoricalProcessExitReasons' "$process_exit_diagnostics"
grep -Fq -- 'isLowMemoryKillReportSupported()' "$process_exit_diagnostics"
grep -Fq -- 'same_uid_rss_kib=' "$process_exit_diagnostics"
grep -Fq -- 'ProcessExitDiagnostics.report(context)' "$repository_source"

echo "v0.9 direct-Binder X11 architecture checks passed"
