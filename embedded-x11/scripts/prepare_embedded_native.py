#!/usr/bin/env python3
"""Generate embedded-only Termux:X11 native sources without modifying the submodule."""

from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: prepare_embedded_native.py <upstream-cpp> <output-dir>")

upstream = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2]).resolve()
out.mkdir(parents=True, exist_ok=True)

cmd_source = upstream / "lorie" / "cmdentrypoint.cpp"
activity_source = upstream / "lorie" / "activity.cpp"
if not cmd_source.is_file() or not activity_source.is_file():
    raise SystemExit("Pinned Termux:X11 native sources are missing")

cmd = cmd_source.read_text(encoding="utf-8")
old_affinity = """        cpu_set_t mask;\n        long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);\n"""
new_affinity = """        cpu_set_t mask;\n        CPU_ZERO(&mask);\n        long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);\n"""
if old_affinity not in cmd:
    raise SystemExit("Termux:X11 affinity block changed; embedded patch needs review")
cmd = cmd.replace(old_affinity, new_affinity, 1)

old_preload = """    if (access(\"/data/data/com.termux/files/usr/lib/libtermux-exec.so\", F_OK) == 0 && !detectTracer()\n            && !getenv(\"XSTARTUP_LD_PRELOAD\"))\n        setenv(\"LD_PRELOAD\", \"/data/data/com.termux/files/usr/lib/libtermux-exec.so\", 1);\n"""
new_preload = """    // LDFA starts Xorg inside a normal Android app process, not from a Termux shell. Injecting\n    // libtermux-exec into LD_PRELOAD is shell-only behavior and can contaminate child processes.\n    // Keep any unrelated platform preload untouched, but never add the Termux exec shim here.\n    if (getenv(\"LD_PRELOAD\") && strstr(getenv(\"LD_PRELOAD\"), \"libtermux-exec.so\"))\n        unsetenv(\"LD_PRELOAD\");\n"""
if old_preload not in cmd:
    raise SystemExit("Termux:X11 LD_PRELOAD block changed; embedded patch needs review")
cmd = cmd.replace(old_preload, new_preload, 1)

old_tmp = """        const char *root_dir = dirname(getenv(\"TMPDIR\"));\n        const char* pathes[] = {\n"""
new_tmp = """        char tmp_copy[1024] = {0};\n        snprintf(tmp_copy, sizeof(tmp_copy), \"%s\", getenv(\"TMPDIR\"));\n        const char *root_dir = dirname(tmp_copy);\n        const char* pathes[] = {\n"""
if old_tmp not in cmd:
    raise SystemExit("Termux:X11 TMPDIR block changed; embedded patch needs review")
cmd = cmd.replace(old_tmp, new_tmp, 1)

old_choreographer = """    AChoreographer *choreographer = AChoreographer_getInstance();\n    // Trigger it first time\n    AChoreographer_postFrameCallback(choreographer, (AChoreographer_frameCallback) lorieChoreographerFrameCallback, choreographer);\n"""
new_choreographer = """    AChoreographer *choreographer = AChoreographer_getInstance();\n    // The X server throttles rendering through this callback. A socket-only server without a\n    // choreographer can accept X11 clients but will never become a usable Android display, so fail\n    // before starting dix_main and let the management layer try legacy/VNC deterministically.\n    if (!choreographer) {\n        log(ERROR, \"LDFA: AChoreographer unavailable during X server startup\");\n        dprintf(2, \"LDFA: AChoreographer unavailable during X server startup\\n\");\n        return JNI_FALSE;\n    }\n    AChoreographer_postFrameCallback(choreographer, (AChoreographer_frameCallback) lorieChoreographerFrameCallback, choreographer);\n"""
if old_choreographer not in cmd:
    raise SystemExit("Termux:X11 choreographer block changed; embedded patch needs review")
cmd = cmd.replace(old_choreographer, new_choreographer, 1)
(out / "cmdentrypoint.cpp").write_text(cmd, encoding="utf-8")

activity = activity_source.read_text(encoding="utf-8")
frame_accessor = r'''

// LDFA startup health probe: returns the number of frames that actually reached eglSwapBuffers
// for the current LorieView. The renderer state pointer is protected by stateLock because the
// renderer thread may replace and unmap the shared state during reconnects.
extern "C" JNIEXPORT jint JNICALL
Java_com_termux_x11_EmbeddedX11Display_nativeRenderedFrames(
        __unused JNIEnv *env, __unused jclass clazz, jlong ptr) {
    auto *resources = (LorieViewResources *) (intptr_t) ptr;
    if (!resources || resources->destroyed)
        return 0;

    pthread_mutex_lock(&resources->renderer.stateLock);
    auto *state = resources->renderer.state;
    jint frames = state ? state->renderedFrames : 0;
    pthread_mutex_unlock(&resources->renderer.stateLock);
    return frames;
}
'''
if "Java_com_termux_x11_EmbeddedX11Display_nativeRenderedFrames" in activity:
    raise SystemExit("Rendered-frame accessor already exists upstream; embedded patch needs review")
activity += frame_accessor
(out / "activity.cpp").write_text(activity, encoding="utf-8")

print(f"Generated embedded native sources in {out}")
