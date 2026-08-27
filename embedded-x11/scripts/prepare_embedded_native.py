#!/usr/bin/env python3
"""Generate crash-hardened embedded Termux:X11 native sources.

The pinned upstream tree remains untouched. Every replacement is guarded so an
upstream update fails the build instead of silently dropping an LDFA safety fix.
"""

from pathlib import Path
import os
import shutil
import sys


if len(sys.argv) != 3:
    raise SystemExit("usage: prepare_embedded_native.py <upstream-cpp> <output-dir>")

upstream = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)

cmd_source = upstream / "lorie" / "cmdentrypoint.cpp"
activity_source = upstream / "lorie" / "activity.cpp"
renderer_source = upstream / "lorie" / "renderer.cpp"
header_source = upstream / "lorie" / "lorie.h"
if (
    not cmd_source.is_file()
    or not activity_source.is_file()
    or not renderer_source.is_file()
    or not header_source.is_file()
):
    raise SystemExit("Pinned Termux:X11 native sources are missing")


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"Termux:X11 {description} changed; expected one match, found {count}"
        )
    return text.replace(old, new, 1)


# Upstream's xkbcomp recipe invokes /usr/bin/gcc directly to build a helper
# that must run on the build host.  That path does not exist on NixOS and the
# Android cross compiler cannot be used for this executable.  Build through a
# generated source overlay so the pinned submodule stays untouched while CMake
# can discover a real host compiler on every supported build machine.
overlay = output / "upstream-cpp"
if overlay.exists() or overlay.is_symlink():
    if overlay.is_symlink() or overlay.is_file():
        overlay.unlink()
    else:
        shutil.rmtree(overlay)
overlay.mkdir(parents=True)
for child in upstream.iterdir():
    if child.name in {"CMakeLists.txt", "recipes", "lorie"}:
        continue
    os.symlink(child, overlay / child.name, target_is_directory=child.is_dir())
shutil.copy2(upstream / "CMakeLists.txt", overlay / "CMakeLists.txt")
shutil.copytree(upstream / "recipes", overlay / "recipes")

# lorie_mutex_lock() is included by both the X server and viewer translation
# units. pthread_mutex_timedlock() interprets its absolute deadline using
# CLOCK_REALTIME on Android; upstream used CLOCK_MONOTONIC, so every ordinary
# contention could look like an immediate timeout and reinitialize a live
# cross-process mutex. Keep one guarded patched header in both include trees.
lorie_overlay = overlay / "lorie"
lorie_overlay.mkdir()
for child in (upstream / "lorie").iterdir():
    if child.name == "lorie.h":
        continue
    os.symlink(child, lorie_overlay / child.name, target_is_directory=child.is_dir())

header = header_source.read_text(encoding="utf-8")
header = replace_once(
    header,
    "bool lorieConnectionAlive(void);\n",
    "bool lorieConnectionAlive(void);\nbool ldfaViewerConnectionAlive(void);\n",
    "viewer connection health declaration",
)
header = replace_once(
    header,
    "        clock_gettime(CLOCK_MONOTONIC, &ts);\n",
    "        clock_gettime(CLOCK_REALTIME, &ts);\n",
    "shared mutex timeout clock",
)
header = replace_once(
    header,
    "#include <errno.h>\n",
    "#include <errno.h>\n#include <signal.h>\n#include <sched.h>\n",
    "shared mutex owner liveness include",
)
header = replace_once(
    header,
    "#include <EGL/egl.h>\n",
    "#include <atomic>\n#include <EGL/egl.h>\n",
    "renderer atomic state include",
)
header = replace_once(
    header,
    "    volatile bool stopping = false;\n",
    "    std::atomic_bool stopping{false};\n",
    "renderer stopping flag",
)
header = replace_once(
    header,
    "    EVENT_GPU_COPY_DONE,\n    EVENT_LOCK_KEYS_STATE,\n",
    "    EVENT_GPU_COPY_DONE,\n    EVENT_LOCK_KEYS_STATE,\n    EVENT_FULL_REDRAW,\n",
    "full redraw event type",
)
header = replace_once(
    header,
    '''        if (ret == ETIMEDOUT) {
            if (*lockingPid == getpid() || lorieConnectionAlive())
                continue;
''',
    '''        if (ret == ETIMEDOUT) {
            pid_t owner = __atomic_load_n(lockingPid, __ATOMIC_ACQUIRE);
            bool ownerAlive = owner > 0 &&
                (kill(owner, 0) == 0 || errno == EPERM);
            if (owner == getpid() || ownerAlive || lorieConnectionAlive())
                continue;
''',
    "shared mutex owner liveness",
)
header = replace_once(
    header,
    '''            // Mutex will be locked fine on the next iteration
        } else {
            *lockingPid = getpid();
            return;
        }
''',
    '''            pthread_mutexattr_destroy(&attr);
            // Mutex will be locked fine on the next iteration.
        } else if (ret == 0) {
            __atomic_store_n(lockingPid, getpid(), __ATOMIC_RELEASE);
            return;
        } else if (ret != EINTR) {
            // Never treat EINVAL/EDEADLK as ownership. Yield and retry while
            // connection/owner liveness prevents destructive recovery.
            sched_yield();
        }
''',
    "shared mutex error handling",
)
header = replace_once(
    header,
    '''static inline __always_inline void lorie_mutex_unlock(pthread_mutex_t* mutex, pid_t* lockingPid) {
    *lockingPid = 0;
    pthread_mutex_unlock(mutex);
}
''',
    '''static inline __always_inline void lorie_mutex_unlock(pthread_mutex_t* mutex, pid_t* lockingPid) {
    __atomic_store_n(lockingPid, 0, __ATOMIC_RELEASE);
    pthread_mutex_unlock(mutex);
}
''',
    "shared mutex owner release",
)
header = replace_once(
    header,
    '''#include <GLES2/gl2.h>
#include "list.h"

struct Renderer {
''',
    '''#include <GLES2/gl2.h>
#include "list.h"

// The X server still uses lorie_mutex_lock() so its dead-owner recovery
// semantics remain unchanged.  The viewer needs a cancellable variant because
// Activity teardown synchronously joins the renderer thread on the UI thread.
static inline __always_inline bool lorie_mutex_lock_interruptible(
        pthread_mutex_t* mutex,
        pid_t* lockingPid,
        const std::atomic_bool* cancelled) {
    struct timespec ts = {0};
    while (true) {
        if (cancelled->load(std::memory_order_acquire))
            return false;

        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += 33UL * 1000000UL;
        if (ts.tv_nsec >= 1000000000L) {
            ts.tv_sec += ts.tv_nsec / 1000000000L;
            ts.tv_nsec %= 1000000000L;
        }

        int ret = pthread_mutex_timedlock(mutex, &ts);
        if (ret == 0) {
            __atomic_store_n(lockingPid, getpid(), __ATOMIC_RELEASE);
            if (cancelled->load(std::memory_order_acquire)) {
                __atomic_store_n(lockingPid, 0, __ATOMIC_RELEASE);
                pthread_mutex_unlock(mutex);
                return false;
            }
            return true;
        }

        if (cancelled->load(std::memory_order_acquire))
            return false;
        if (ret == ETIMEDOUT) {
            pid_t owner = __atomic_load_n(lockingPid, __ATOMIC_ACQUIRE);
            bool ownerAlive = owner > 0 &&
                (kill(owner, 0) == 0 || errno == EPERM);
            if (owner == getpid() || ownerAlive || lorieConnectionAlive())
                continue;

            pthread_mutexattr_t attr;
            pthread_mutex_t initializer = PTHREAD_MUTEX_INITIALIZER;
            pthread_mutexattr_init(&attr);
            pthread_mutexattr_setpshared(&attr, PTHREAD_PROCESS_SHARED);
            pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
            memcpy(mutex, &initializer, sizeof(initializer));
            pthread_mutex_init(mutex, &attr);
            pthread_mutexattr_destroy(&attr);
        } else if (ret != EINTR) {
            sched_yield();
        }
    }
}

struct Renderer {
''',
    "interruptible renderer mutex",
)
(lorie_overlay / "lorie.h").write_text(header, encoding="utf-8")
(output / "lorie.h").write_text(header, encoding="utf-8")

xkb_recipe = overlay / "recipes" / "xkbcomp.cmake"
xkb_text = xkb_recipe.read_text(encoding="utf-8")
xkb_text = replace_once(
    xkb_text,
    'file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/X11")\n',
    '''file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/X11")

# makekeys is executed while cross-compiling, so it must be built with a host
# compiler rather than the Android NDK compiler.
find_program(LDFA_HOST_C_COMPILER NAMES cc gcc REQUIRED)
''',
    "host compiler discovery",
)
xkb_text = replace_once(
    xkb_text,
    '        COMMAND "/usr/bin/gcc" "-o"',
    '        COMMAND "${LDFA_HOST_C_COMPILER}" "-o"',
    "hard-coded host compiler",
)
xkb_recipe.write_text(xkb_text, encoding="utf-8")


# ---- X server entrypoint -------------------------------------------------
cmd = cmd_source.read_text(encoding="utf-8")
cmd = replace_once(
    cmd,
    "#include <randrstr.h>\n",
    "#include <randrstr.h>\n#include <pixmapstr.h>\n",
    "root pixmap damage include",
)
cmd = replace_once(
    cmd,
    """        cpu_set_t mask;
        long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);
""",
    """        cpu_set_t mask;
        CPU_ZERO(&mask);
        long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);
""",
    "CPU affinity block",
)
cmd = replace_once(
    cmd,
    """    if (access("/data/data/com.termux/files/usr/lib/libtermux-exec.so", F_OK) == 0 && !detectTracer()
            && !getenv("XSTARTUP_LD_PRELOAD"))
        setenv("LD_PRELOAD", "/data/data/com.termux/files/usr/lib/libtermux-exec.so", 1);
""",
    """    // This is a regular Android process, not a Termux shell. Never inject the
    // shell-only exec shim into Xorg or descendants.
    if (getenv("LD_PRELOAD") && strstr(getenv("LD_PRELOAD"), "libtermux-exec.so"))
        unsetenv("LD_PRELOAD");
""",
    "LD_PRELOAD block",
)
cmd = replace_once(
    cmd,
    """        const char *root_dir = dirname(getenv("TMPDIR"));
        const char* pathes[] = {
""",
    """        char tmp_copy[1024] = {0};
        snprintf(tmp_copy, sizeof(tmp_copy), "%s", getenv("TMPDIR"));
        const char *root_dir = dirname(tmp_copy);
        const char* pathes[] = {
""",
    "TMPDIR block",
)
cmd = replace_once(
    cmd,
    """    AChoreographer *choreographer = AChoreographer_getInstance();
    // Trigger it first time
    AChoreographer_postFrameCallback(choreographer, (AChoreographer_frameCallback) lorieChoreographerFrameCallback, choreographer);
""",
    """    AChoreographer *choreographer = AChoreographer_getInstance();
    if (!choreographer) {
        log(ERROR, "LDFA: AChoreographer unavailable during X server startup");
        dprintf(2, "LDFA: AChoreographer unavailable during X server startup\\n");
        return JNI_FALSE;
    }
    AChoreographer_postFrameCallback(choreographer, (AChoreographer_frameCallback) lorieChoreographerFrameCallback, choreographer);
""",
    "choreographer block",
)

# An old fd can report HUP after a new viewer has connected. Only the current
# generation may clear global connection state.
cmd = replace_once(
    cmd,
    """    if (ready & X_NOTIFY_ERROR) {
        LorieBuffer* buf;
        InputThreadUnregisterDev(fd);
        close(fd);
        conn_fd = -1;
        lorieEnableClipboardSync(FALSE);
        while ((buf = LorieBufferList_first(&registeredBuffers)))
            LorieBuffer_removeFromList(buf);
        return;
    }
""",
    """    if (ready & X_NOTIFY_ERROR) {
        InputThreadUnregisterDev(fd);
        if (conn_fd == fd) {
            LorieBuffer* buf;
            shutdown(fd, SHUT_RDWR);
            close(fd);
            conn_fd = -1;
            lorieEnableClipboardSync(FALSE);
            while ((buf = LorieBufferList_first(&registeredBuffers)))
                LorieBuffer_removeFromList(buf);
        }
        return;
    }
""",
    "X connection error handler",
)
cmd = replace_once(cmd, "read(conn_fd, data, e.clipboardSend.count);", "read(fd, data, e.clipboardSend.count);", "clipboard fd read")
cmd = replace_once(
    cmd,
    """            case EVENT_TOUCH: {
""",
    """            case EVENT_FULL_REDRAW:
                QueueWorkProc(+[](__unused ClientPtr pClient, __unused void *closure) -> Bool {
                    // A newly-created Android Surface can initially sample a stale
                    // AHardwareBuffer texture even though the X root pixmap is intact.
                    // Damage the complete root on the X-server thread so lorieRedraw()
                    // performs its required unlock/lock cache synchronization and
                    // presents the existing desktop without waiting for an X client.
                    PixmapPtr root = pScreenPtr && pScreenPtr->root
                        ? pScreenPtr->GetWindowPixmap(pScreenPtr->root)
                        : nullptr;
                    if (root && root->drawable.width > 0 && root->drawable.height > 0) {
                        RegionRec region;
                        PixmapRegionInit(&region, root);
                        DamageDamageRegion(&root->drawable, &region);
                        RegionUninit(&region);
                        log(DEBUG, "LDFA: requested full root redraw after Surface resume");
                    }
                    return TRUE;
                }, nullptr, nullptr);
                lorieWakeServer();
                break;
            case EVENT_TOUCH: {
""",
    "full redraw event handler",
)

old_get_connection = """extern "C" JNIEXPORT jobject JNICALL
Java_com_termux_x11_CmdEntryPoint_getXConnection(JNIEnv *env, __unused jobject cls) {
    int client[2];
    jclass ParcelFileDescriptorClass = env->FindClass("android/os/ParcelFileDescriptor");
    jmethodID adoptFd = env->GetStaticMethodID(ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;");
    socketpair(AF_UNIX, SOCK_STREAM, 0, client);
    QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
        InputThreadRegisterDev((int) (int64_t) closure, handleLorieEvents, nullptr);
        conn_fd = (int) (int64_t) closure;
        lorieActivityConnected();
        return TRUE;
    }, nullptr, (void*) (int64_t) client[1]);
    lorieWakeServer();

    return env->CallStaticObjectMethod(ParcelFileDescriptorClass, adoptFd, client[0]);
}
"""
new_get_connection = """extern "C" JNIEXPORT jobject JNICALL
Java_com_termux_x11_CmdEntryPoint_getXConnection(JNIEnv *env, __unused jobject cls) {
    int client[2] = {-1, -1};
    jclass ParcelFileDescriptorClass = env->FindClass("android/os/ParcelFileDescriptor");
    jmethodID adoptFd = ParcelFileDescriptorClass
        ? env->GetStaticMethodID(ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;")
        : nullptr;
    if (!ParcelFileDescriptorClass || !adoptFd ||
        socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, client) != 0)
        return nullptr;

    if (!QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
        int next_fd = (int) (int64_t) closure;
        if (conn_fd != -1 && conn_fd != next_fd) {
            InputThreadUnregisterDev(conn_fd);
            shutdown(conn_fd, SHUT_RDWR);
            close(conn_fd);
            LorieBuffer* buf;
            while ((buf = LorieBufferList_first(&registeredBuffers)))
                LorieBuffer_removeFromList(buf);
        }
        if (!InputThreadRegisterDev(next_fd, handleLorieEvents, nullptr)) {
            close(next_fd);
            return TRUE;
        }
        conn_fd = next_fd;
        lorieActivityConnected();
        return TRUE;
    }, nullptr, (void*) (int64_t) client[1])) {
        close(client[0]);
        close(client[1]);
        return nullptr;
    }
    lorieWakeServer();

    return env->CallStaticObjectMethod(ParcelFileDescriptorClass, adoptFd, client[0]);
}
"""
cmd = replace_once(cmd, old_get_connection, new_get_connection, "getXConnection implementation")
cmd = replace_once(
    cmd,
    '''bool lorieConnectionAlive(void) {
    if (conn_fd == -1)
        return false;

    // Check if socket is closed or has errors.
    struct pollfd p = { .fd = conn_fd, .events = POLLIN | POLLHUP | POLLERR | POLLRDHUP };
    return !(poll(&p, 1, 0) == 1 && (p.revents & (POLLERR | POLLNVAL | POLLRDHUP | POLLHUP)));
}
''',
    '''bool lorieConnectionAlive(void) {
    bool serverAlive = false;
    if (conn_fd != -1) {
        // Check if the X-server side of the socket is closed or has errors.
        struct pollfd p = { .fd = conn_fd, .events = POLLIN | POLLHUP | POLLERR | POLLRDHUP };
        serverAlive = !(poll(&p, 1, 0) == 1 &&
                        (p.revents & (POLLERR | POLLNVAL | POLLRDHUP | POLLHUP)));
    }

    // activity.cpp owns a different fd in the Android viewer process. Without
    // this second check the renderer considers every shared-mutex timeout a
    // dead peer and can reinitialize a mutex the live X server still owns.
    return serverAlive || ldfaViewerConnectionAlive();
}
''',
    "combined server/viewer connection health",
)
(output / "cmdentrypoint.cpp").write_text(cmd, encoding="utf-8")


# ---- Android viewer renderer --------------------------------------------
renderer = renderer_source.read_text(encoding="utf-8")
renderer = replace_once(
    renderer,
    "#include <cmath>\n#include <cstring>\n",
    "#include <cmath>\n#include <cstring>\n#include <cerrno>\n#include <ctime>\n",
    "renderer timeout includes",
)
renderer = replace_once(
    renderer,
    '''void Renderer::notifyGpuCopyDone() {
    if (connFdPtr && *connFdPtr != -1) {
        lorieEvent e = { .type = EVENT_GPU_COPY_DONE };
        write(*connFdPtr, &e, sizeof(e));
    }
}
''',
    '''void Renderer::notifyGpuCopyDone() {
    int fd = connFdPtr ? __atomic_load_n(connFdPtr, __ATOMIC_ACQUIRE) : -1;
    if (fd != -1) {
        lorieEvent e = { .type = EVENT_GPU_COPY_DONE };
        // Never let a stalled X server pin the renderer thread and in turn the
        // Activity main thread in pthread_join().
        send(fd, &e, sizeof(e), MSG_DONTWAIT | MSG_NOSIGNAL);
    }
}
''',
    "nonblocking GPU completion notification",
)
renderer_health = r'''

enum LdfaRendererInitState {
    LDFA_RENDERER_STOPPED = 0,
    LDFA_RENDERER_STARTING = 1,
    LDFA_RENDERER_READY = 2,
    LDFA_RENDERER_FAILED = 3,
    LDFA_RENDERER_STOPPING = 4,
};

// One LorieView renderer exists in the viewer process. These values deliberately
// do not live in lorie_shared_server_state: renderedFrames is a rolling FPS counter.
static volatile int ldfaRendererInitState = LDFA_RENDERER_STOPPED;
static volatile uint64_t ldfaSuccessfulPresentSerial = 0;
// Bound a stalled vendor GPU fence below Android's ANR window. A frame that cannot finish within
// one second is already unusable and transitions the renderer to FAILED.
static const EGLTimeKHR LDFA_GPU_FENCE_TIMEOUT_NS = 1000000000ULL;
static AImageReader* ldfaDefaultImageReader = nullptr;

extern "C" uint64_t ldfaGetSuccessfulPresentSerial() {
    return __atomic_load_n(&ldfaSuccessfulPresentSerial, __ATOMIC_ACQUIRE);
}

extern "C" void ldfaResetSuccessfulPresentSerial() {
    __atomic_store_n(&ldfaSuccessfulPresentSerial, 0, __ATOMIC_RELEASE);
}

extern "C" bool ldfaRendererIsReady() {
    return __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) == LDFA_RENDERER_READY;
}

extern "C" bool ldfaRendererAcceptsConnection() {
    int state = __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE);
    return state == LDFA_RENDERER_STARTING || state == LDFA_RENDERER_READY;
}

static void ldfaMarkRendererFailedLocked(Renderer* renderer, const char* stage) {
    loge("LDFA renderer failed at %s", stage);
    renderer->stopping = true;
    __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_FAILED, __ATOMIC_RELEASE);
    pthread_cond_broadcast(&renderer->stateChangeFinishCond);
    if (renderer->stateCond)
        pthread_cond_signal(renderer->stateCond);
}

static void ldfaMarkRendererFailed(Renderer* renderer, const char* stage) {
    pthread_mutex_lock(&renderer->stateLock);
    ldfaMarkRendererFailedLocked(renderer, stage);
    pthread_mutex_unlock(&renderer->stateLock);
}

static void ldfaPublishRendererFailure(Renderer* renderer, const char* stage) {
    ldfaMarkRendererFailed(renderer, stage);
    pthread_mutex_lock(&renderer->stateLock);
    struct lorie_shared_server_state* abandonedState = renderer->pendingState;
    ANativeWindow* abandonedWindow = renderer->pendingWin;
    renderer->pendingState = nullptr;
    renderer->pendingWin = nullptr;
    renderer->stateChanged = false;
    renderer->windowChanged = false;
    pthread_mutex_unlock(&renderer->stateLock);
    if (abandonedState)
        munmap(abandonedState, sizeof(*abandonedState));
    if (abandonedWindow)
        ANativeWindow_release(abandonedWindow);
    pthread_spin_lock(&renderer->bufferLock);
    LorieBuffer* abandonedBuffer;
    while ((abandonedBuffer = LorieBufferList_first(&renderer->addedBuffers)))
        LorieBuffer_release(abandonedBuffer);
    pthread_spin_unlock(&renderer->bufferLock);

    // EGL/GL objects are thread-affine. Release any partially-created objects
    // here, before the failed renderer thread exits; destroy() will still join it.
    if (renderer->rendererEnv) {
        if (renderer->g_texture_program)
            glDeleteProgram(renderer->g_texture_program);
        if (renderer->g_texture_program_bgra)
            glDeleteProgram(renderer->g_texture_program_bgra);
        renderer->g_texture_program = renderer->g_texture_program_bgra = 0;
        if (renderer->egl_display != EGL_NO_DISPLAY) {
            eglMakeCurrent(renderer->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (renderer->sfc != EGL_NO_SURFACE && renderer->sfc != renderer->defaultSfc)
                eglDestroySurface(renderer->egl_display, renderer->sfc);
            if (renderer->defaultSfc != EGL_NO_SURFACE)
                eglDestroySurface(renderer->egl_display, renderer->defaultSfc);
            if (renderer->ctx != EGL_NO_CONTEXT)
                eglDestroyContext(renderer->egl_display, renderer->ctx);
        }
        if (renderer->win && renderer->win != renderer->defaultWin)
            ANativeWindow_release(renderer->win);
        if (renderer->defaultWin)
            ANativeWindow_release(renderer->defaultWin);
        if (ldfaDefaultImageReader) {
            AImageReader_delete(ldfaDefaultImageReader);
            ldfaDefaultImageReader = nullptr;
        }
        if (renderer->lorieViewClass) {
            renderer->rendererEnv->DeleteGlobalRef(renderer->lorieViewClass);
            renderer->lorieViewClass = nullptr;
        }
        renderer->defaultWin = renderer->win = nullptr;
        renderer->defaultSfc = renderer->sfc = EGL_NO_SURFACE;
        renderer->ctx = EGL_NO_CONTEXT;
        renderer->egl_display = EGL_NO_DISPLAY;
        renderer->jvm->DetachCurrentThread();
        renderer->rendererEnv = nullptr;
    }
}
'''
renderer = replace_once(
    renderer,
    "static GLuint createProgram(const char* p_vertex_source, const char* p_fragment_source);\n",
    "static GLuint createProgram(const char* p_vertex_source, const char* p_fragment_source);\n" + renderer_health,
    "renderer health declarations",
)
renderer = replace_once(
    renderer,
    '''            } else if (AImageReader_getWindow(reader, &win) != AMEDIA_OK) {
                log("Failed to obtain ImageReader native window");
                AImageReader_delete(reader);
            }
        }

        if (win)
            return win;
''',
    '''            } else if (AImageReader_getWindow(reader, &win) != AMEDIA_OK) {
                log("Failed to obtain ImageReader native window");
                AImageReader_delete(reader);
            }
        }

        if (win) {
            // The ImageReader owns this window; acquire one renderer-owned ref.
            ANativeWindow_acquire(win);
            ldfaDefaultImageReader = reader;
            return win;
        }
''',
    "default ImageReader ownership",
)
renderer = replace_once(
    renderer,
    '''    env->NewGlobalRef(surfaceTexture);
    env->NewGlobalRef(surface);
    return ANativeWindow_fromSurface(env, surface);
''',
    '''    // ANativeWindow_fromSurface returns one acquired native reference. JNI
    // local references are released when the renderer thread detaches.
    return ANativeWindow_fromSurface(env, surface);
''',
    "fallback Surface ownership",
)
renderer = replace_once(
    renderer,
    """    if (jvm->AttachCurrentThread(&rendererEnv, nullptr) != JNI_OK) {
        log("Failed to attach renderer thread to JVM");
        return nullptr;
    }
""",
    """    if (jvm->AttachCurrentThread(&rendererEnv, nullptr) != JNI_OK) {
        ldfaPublishRendererFailure(this, "AttachCurrentThread");
        return nullptr;
    }
""",
    "renderer JVM attach",
)
renderer = replace_once(
    renderer,
    '''    pthread_setname_np(pthread_self(), "LorieRendererThread");

    xorg_list_init(&addedBuffers);
    xorg_list_init(&buffers);
    xorg_list_init(&removedBuffers);
''',
    '''    pthread_setname_np(pthread_self(), "LorieRendererThread");
''',
    "renderer list initialization",
)

early_failures = [
    (
        """    if (egl_display == EGL_NO_DISPLAY)
        return printEglError("Got no EGL display", __LINE__);
""",
        """    if (egl_display == EGL_NO_DISPLAY) {
        printEglError("Got no EGL display", __LINE__);
        ldfaPublishRendererFailure(this, "eglGetDisplay");
        return nullptr;
    }
""",
        "eglGetDisplay",
    ),
    (
        """    if (eglInitialize(egl_display, &major, &minor) != EGL_TRUE)
        return printEglError("Unable to initialize EGL", __LINE__);
""",
        """    if (eglInitialize(egl_display, &major, &minor) != EGL_TRUE) {
        printEglError("Unable to initialize EGL", __LINE__);
        ldfaPublishRendererFailure(this, "eglInitialize");
        return nullptr;
    }
""",
        "eglInitialize",
    ),
    (
        """    if (eglChooseConfig(egl_display, configAttribs, &cfg, 1, &numConfigs) != EGL_TRUE &&
        (*alphaAttrib = 8) &&
        eglChooseConfig(egl_display, configAttribs, &cfg, 1, &numConfigs) != EGL_TRUE)
        return printEglError("eglChooseConfig failed", __LINE__);
""",
        """    if (eglChooseConfig(egl_display, configAttribs, &cfg, 1, &numConfigs) != EGL_TRUE || numConfigs < 1) {
        *alphaAttrib = 8;
        if (eglChooseConfig(egl_display, configAttribs, &cfg, 1, &numConfigs) != EGL_TRUE || numConfigs < 1) {
            printEglError("eglChooseConfig failed", __LINE__);
            ldfaPublishRendererFailure(this, "eglChooseConfig");
            return nullptr;
        }
    }
""",
        "eglChooseConfig",
    ),
    (
        """    if (ctx == EGL_NO_CONTEXT)
        return printEglError("eglCreateContext failed", __LINE__);
""",
        """    if (ctx == EGL_NO_CONTEXT) {
        printEglError("eglCreateContext failed", __LINE__);
        ldfaPublishRendererFailure(this, "eglCreateContext");
        return nullptr;
    }
""",
        "eglCreateContext",
    ),
    (
        """    if (!defaultWin)
        return printEglError("Got no window to keep the context current on", __LINE__);
""",
        """    if (!defaultWin) {
        printEglError("Got no window to keep the context current on", __LINE__);
        ldfaPublishRendererFailure(this, "createDefaultWindow");
        return nullptr;
    }
""",
        "default window",
    ),
]
for old, new, description in early_failures:
    renderer = replace_once(renderer, old, new, description)

renderer = replace_once(
    renderer,
    "    ANativeWindow_acquire(defaultWin);\n\n",
    "",
    "default window duplicate reference",
)

renderer = replace_once(
    renderer,
    """    sfc = defaultSfc = eglCreateWindowSurface(egl_display, cfg, win, nullptr);

    eglMakeCurrent(egl_display, sfc, sfc, ctx);
    eglSwapInterval(egl_display, 0);
""",
    """    sfc = defaultSfc = eglCreateWindowSurface(egl_display, cfg, win, nullptr);
    if (defaultSfc == EGL_NO_SURFACE) {
        printEglError("eglCreateWindowSurface failed", __LINE__);
        ldfaPublishRendererFailure(this, "eglCreateWindowSurface");
        return nullptr;
    }
    if (eglMakeCurrent(egl_display, sfc, sfc, ctx) != EGL_TRUE) {
        printEglError("eglMakeCurrent failed", __LINE__);
        ldfaPublishRendererFailure(this, "eglMakeCurrent");
        return nullptr;
    }
    eglSwapInterval(egl_display, 0);
""",
    "default EGL surface",
)
renderer = replace_once(
    renderer,
    """    g_texture_program = createProgram(vertexShaderSrc, fragmentShaderSrc);
    if (!g_texture_program)
        log("Xlorie: GLESv2: Unable to create shader program.\\n");

    g_texture_program_bgra = createProgram(vertexShaderSrc, fragmentShaderBgraSrc);
    if (!g_texture_program_bgra)
        log("Xlorie: GLESv2: Unable to create bgra shader program.\\n");
""",
    """    g_texture_program = createProgram(vertexShaderSrc, fragmentShaderSrc);
    if (!g_texture_program) {
        ldfaPublishRendererFailure(this, "RGBA shader program");
        return nullptr;
    }

    g_texture_program_bgra = createProgram(vertexShaderSrc, fragmentShaderBgraSrc);
    if (!g_texture_program_bgra) {
        ldfaPublishRendererFailure(this, "BGRA shader program");
        return nullptr;
    }
""",
    "shader initialization",
)
renderer = replace_once(
    renderer,
    """    glActiveTexture(GL_TEXTURE0);
    glGenTextures(1, &cursor.id);

    threadLoop();
    return nullptr;
""",
    """    glActiveTexture(GL_TEXTURE0);
    glGenTextures(1, &cursor.id);

    pthread_mutex_lock(&stateLock);
    int expectedState = LDFA_RENDERER_STARTING;
    bool becameReady = !stopping && __atomic_compare_exchange_n(
        &ldfaRendererInitState, &expectedState, LDFA_RENDERER_READY, false,
        __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
    if (becameReady)
        pthread_cond_broadcast(&stateChangeFinishCond);
    pthread_mutex_unlock(&stateLock);
    if (!becameReady) {
        ldfaPublishRendererFailure(this, "cancelled before READY");
        return nullptr;
    }
    threadLoop();
    if (__atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_FAILED)
        __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_STOPPED, __ATOMIC_RELEASE);
    return nullptr;
""",
    "renderer thread entry",
)
renderer = replace_once(
    renderer,
    """void Renderer::init(JNIEnv* env) {
    if (ctx)
        return;
""",
    """void Renderer::init(JNIEnv* env) {
    if (thread)
        return;

    ldfaResetSuccessfulPresentSerial();
    __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_STARTING, __ATOMIC_RELEASE);
""",
    "renderer init guard",
)
renderer = replace_once(
    renderer,
    '''    pthread_mutex_init(&stateLock, nullptr);

    // Created once, never recreated; only the fd is (re)sent to the X server whenever it (re)connects.
    pthread_condattr_t cond_attr;
    pthread_condattr_init(&cond_attr);
    pthread_condattr_setpshared(&cond_attr, PTHREAD_PROCESS_SHARED);
    stateCondFd = LorieBuffer_createRegion("renderer-cond", sizeof(pthread_cond_t));
    stateCond = stateCondFd == -1 ? (pthread_cond_t*) MAP_FAILED : (pthread_cond_t*) mmap(nullptr, sizeof(pthread_cond_t), PROT_READ|PROT_WRITE, MAP_SHARED, stateCondFd, 0);
    if (stateCond == MAP_FAILED) {
        loge("Failed to allocate renderer wakeup cond var, aborting");
        abort();
    }
    pthread_cond_init(stateCond, &cond_attr);

    pthread_cond_init(&stateChangeFinishCond, nullptr);
    pthread_spin_init(&bufferLock, false);
''',
    '''    pthread_mutex_init(&stateLock, nullptr);
    pthread_cond_init(&stateChangeFinishCond, nullptr);
    pthread_spin_init(&bufferLock, false);
    xorg_list_init(&addedBuffers);
    xorg_list_init(&buffers);
    xorg_list_init(&removedBuffers);

    // Created once, never recreated; only the fd is (re)sent to the X server whenever it (re)connects.
    pthread_condattr_t cond_attr;
    pthread_condattr_init(&cond_attr);
    pthread_condattr_setpshared(&cond_attr, PTHREAD_PROCESS_SHARED);
    stateCondFd = LorieBuffer_createRegion("renderer-cond", sizeof(pthread_cond_t));
    stateCond = stateCondFd == -1 ? (pthread_cond_t*) MAP_FAILED : (pthread_cond_t*) mmap(nullptr, sizeof(pthread_cond_t), PROT_READ|PROT_WRITE, MAP_SHARED, stateCondFd, 0);
    if (stateCond == MAP_FAILED) {
        loge("Failed to allocate renderer wakeup cond var");
        stateCond = nullptr;
        if (stateCondFd != -1) {
            close(stateCondFd);
            stateCondFd = -1;
        }
        pthread_condattr_destroy(&cond_attr);
        __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_FAILED, __ATOMIC_RELEASE);
        pthread_cond_broadcast(&stateChangeFinishCond);
        return;
    }
    pthread_cond_init(stateCond, &cond_attr);
    pthread_condattr_destroy(&cond_attr);
''',
    "renderer synchronization initialization",
)
renderer = replace_once(
    renderer,
    """    stopping = false;
    pthread_create(&thread, nullptr, +[](void* cookie) -> void* {
        return ((Renderer*) cookie)->initThread();
    }, this);
}

void Renderer::destroy() {
    if (!ctx)
        return; // never initialized, or already destroyed

    pthread_mutex_lock(&stateLock);
    stopping = true;
    pthread_cond_signal(stateCond);
    pthread_mutex_unlock(&stateLock);

    pthread_join(thread, nullptr);
    thread = 0;
    ctx = EGL_NO_CONTEXT; // re-passes init()'s `if (ctx) return;` guard for a future re-init
}
""",
    """    stopping = false;
    int createResult = pthread_create(&thread, nullptr, +[](void* cookie) -> void* {
        return ((Renderer*) cookie)->initThread();
    }, this);
    if (createResult != 0) {
        thread = 0;
        ldfaPublishRendererFailure(this, "pthread_create");
    }
}

void Renderer::destroy() {
    // Publish cancellation before waiting for stateLock.  The renderer may be
    // blocked in a cross-process mutex while destroy() is waiting here; its
    // interruptible lock path must be able to observe teardown independently.
    stopping.store(true, std::memory_order_release);
    __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_STOPPING, __ATOMIC_RELEASE);
    pthread_mutex_lock(&stateLock);
    struct lorie_shared_server_state* abandonedState = pendingState;
    ANativeWindow* abandonedWindow = pendingWin;
    pendingState = nullptr;
    pendingWin = nullptr;
    stateChanged = false;
    windowChanged = false;
    pthread_cond_broadcast(&stateChangeFinishCond);
    if (stateCond)
        pthread_cond_signal(stateCond);
    pthread_mutex_unlock(&stateLock);

    if (thread) {
        pthread_join(thread, nullptr);
        thread = 0;
    }
    if (abandonedState)
        munmap(abandonedState, sizeof(*abandonedState));
    if (abandonedWindow)
        ANativeWindow_release(abandonedWindow);

    ctx = EGL_NO_CONTEXT;
    if (stateCond) {
        munmap(stateCond, sizeof(pthread_cond_t));
        stateCond = nullptr;
    }
    if (stateCondFd != -1) {
        close(stateCondFd);
        stateCondFd = -1;
    }
    pthread_cond_destroy(&stateChangeFinishCond);
    pthread_spin_destroy(&bufferLock);
    pthread_mutex_destroy(&stateLock);
    if (lorieViewClass && jvm) {
        JNIEnv* cleanupEnv = nullptr;
        if (jvm->GetEnv((void**) &cleanupEnv, JNI_VERSION_1_6) == JNI_OK && cleanupEnv)
            cleanupEnv->DeleteGlobalRef(lorieViewClass);
        lorieViewClass = nullptr;
    }
    connFdPtr = nullptr;
    __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_STOPPED, __ATOMIC_RELEASE);
}
""",
    "renderer lifecycle",
)
renderer = replace_once(
    renderer,
    '''void Renderer::setSharedState(struct lorie_shared_server_state* newState) {
    pthread_mutex_lock(&stateLock);
''',
    '''void Renderer::setSharedState(struct lorie_shared_server_state* newState) {
    if (!stateCond) {
        if (newState)
            munmap(newState, sizeof(*newState));
        return;
    }
    pthread_mutex_lock(&stateLock);
    int initState = __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE);
    if (!stateCond || (initState != LDFA_RENDERER_STARTING && initState != LDFA_RENDERER_READY)) {
        pthread_mutex_unlock(&stateLock);
        if (newState)
            munmap(newState, sizeof(*newState));
        return;
    }
''',
    "shared state failure guard",
)
renderer = replace_once(
    renderer,
    '''void Renderer::addBuffer(LorieBuffer* buf) {
    pthread_spin_lock(&bufferLock);
''',
    '''void Renderer::addBuffer(LorieBuffer* buf) {
    int initState = __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE);
    if (!buf)
        return;
    if (!stateCond || (initState != LDFA_RENDERER_STARTING && initState != LDFA_RENDERER_READY)) {
        LorieBuffer_release(buf);
        return;
    }
    pthread_spin_lock(&bufferLock);
''',
    "buffer failure guard",
)
renderer = replace_once(
    renderer,
    '''    ANativeWindow* newWin = jsfc ? ANativeWindow_fromSurface(env, jsfc) : nullptr;
    if (newWin)
        ANativeWindow_acquire(newWin);

    pthread_mutex_lock(&stateLock);
''',
    '''    ANativeWindow* newWin = jsfc ? ANativeWindow_fromSurface(env, jsfc) : nullptr;
    if (!stateCond) {
        if (newWin)
            ANativeWindow_release(newWin);
        return;
    }
    pthread_mutex_lock(&stateLock);
    int initState = __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE);
    if (!stateCond || (initState != LDFA_RENDERER_STARTING && initState != LDFA_RENDERER_READY)) {
        pthread_mutex_unlock(&stateLock);
        if (newWin)
            ANativeWindow_release(newWin);
        return;
    }
''',
    "window failure guard",
)
renderer = replace_once(
    renderer,
    '''void Renderer::setViewport(int x, int y, int w, int h, int ew, int eh, int hidden) {
    pthread_mutex_lock(&stateLock);
''',
    '''void Renderer::setViewport(int x, int y, int w, int h, int ew, int eh, int hidden) {
    if (!stateCond)
        return;
    pthread_mutex_lock(&stateLock);
    if (!stateCond || __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_READY) {
        pthread_mutex_unlock(&stateLock);
        return;
    }
''',
    "viewport failure guard",
)
renderer = replace_once(
    renderer,
    '''void Renderer::setZoom(int percent) {
    pthread_mutex_lock(&stateLock);
''',
    '''void Renderer::setZoom(int percent) {
    if (!stateCond)
        return;
    pthread_mutex_lock(&stateLock);
    if (!stateCond || __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_READY) {
        pthread_mutex_unlock(&stateLock);
        return;
    }
''',
    "zoom failure guard",
)
renderer = replace_once(
    renderer,
    '''    sfc = eglCreateWindowSurface(egl_display, cfg, win, nullptr);
    if (sfc == EGL_NO_SURFACE)
        return vprintEglError("eglCreateWindowSurface failed", __LINE__);

    if (eglMakeCurrent(egl_display, sfc, sfc, ctx) != EGL_TRUE) {
        if (state)
            state->surfaceAvailable = false;
        notifyGpuCopyDone();
        return vprintEglError("eglMakeCurrent failed", __LINE__);
    }
''',
    '''    sfc = eglCreateWindowSurface(egl_display, cfg, win, nullptr);
    if (sfc == EGL_NO_SURFACE) {
        vprintEglError("eglCreateWindowSurface failed", __LINE__);
        ldfaMarkRendererFailedLocked(this, "refresh eglCreateWindowSurface");
        return;
    }

    if (eglMakeCurrent(egl_display, sfc, sfc, ctx) != EGL_TRUE) {
        if (state)
            state->surfaceAvailable = false;
        notifyGpuCopyDone();
        vprintEglError("eglMakeCurrent failed", __LINE__);
        ldfaMarkRendererFailedLocked(this, "refresh eglMakeCurrent");
        return;
    }
''',
    "runtime EGL surface failure",
)
renderer = replace_once(
    renderer,
    '''    for (attempt = 0; attempt < 20 && !buf; attempt++) {
''',
    '''    for (attempt = 0;
         attempt < 20 && !buf && !stopping.load(std::memory_order_acquire);
         attempt++) {
''',
    "buffer retry cancellation",
)
renderer = replace_once(
    renderer,
    '''    lorie_mutex_lock(&state->lock, &state->lockingPid);
    serial = applyPendingGpuCopiesLocked();
''',
    '''    if (!lorie_mutex_lock_interruptible(
            &state->lock, &state->lockingPid, &stopping))
        return;
    serial = applyPendingGpuCopiesLocked();
''',
    "standalone GPU-copy interruptible lock",
)
renderer = replace_once(
    renderer,
    '''    // We should signal X server to not use root window while we actively copy it
    lorie_mutex_lock(&state->lock, &state->lockingPid);
    // Share this draw's flush+fence below instead of a separate round trip per frame.
''',
    '''    // We should signal X server to not use root window while we actively copy it.
    if (!lorie_mutex_lock_interruptible(
            &state->lock, &state->lockingPid, &stopping))
        return;
    // Share this draw's flush+fence below instead of a separate round trip per frame.
''',
    "root-window interruptible lock",
)
renderer = replace_once(
    renderer,
    r'''    if (state->cursor.updated) {
        log("Xlorie: updating cursor\n");
        lorie_mutex_lock(&state->cursor.lock, &state->cursor.lockingPid);
        state->cursor.updated = false;
''',
    r'''    if (state->cursor.updated) {
        log("Xlorie: updating cursor\n");
        if (!lorie_mutex_lock_interruptible(
                &state->cursor.lock, &state->cursor.lockingPid, &stopping)) {
            if (fence != EGL_NO_SYNC_KHR)
                eglDestroySyncKHR(egl_display, fence);
            lorie_mutex_unlock(&state->lock, &state->lockingPid);
            return;
        }
        state->cursor.updated = false;
''',
    "cursor interruptible lock",
)
renderer = replace_once(
    renderer,
    """    while(stateChanged)
        pthread_cond_wait(&stateChangeFinishCond, &stateLock);
""",
    """    struct timespec stateDeadline;
    clock_gettime(CLOCK_REALTIME, &stateDeadline);
    stateDeadline.tv_sec += 1;
    while (stateChanged &&
           __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_FAILED &&
           __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_STOPPING &&
           __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_STOPPED) {
        if (pthread_cond_timedwait(&stateChangeFinishCond, &stateLock, &stateDeadline) == ETIMEDOUT) {
            loge("Timed out waiting for renderer shared-state acknowledgement");
            stopping = true;
            __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_FAILED, __ATOMIC_RELEASE);
            pthread_cond_broadcast(&stateChangeFinishCond);
            if (stateCond)
                pthread_cond_signal(stateCond);
            break;
        }
    }
""",
    "shared state wait",
)
renderer = replace_once(
    renderer,
    """    while(windowChanged)
        pthread_cond_wait(&stateChangeFinishCond, &stateLock);
""",
    """    struct timespec windowDeadline;
    clock_gettime(CLOCK_REALTIME, &windowDeadline);
    windowDeadline.tv_sec += 1;
    while (windowChanged &&
           __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_FAILED &&
           __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_STOPPING &&
           __atomic_load_n(&ldfaRendererInitState, __ATOMIC_ACQUIRE) != LDFA_RENDERER_STOPPED) {
        if (pthread_cond_timedwait(&stateChangeFinishCond, &stateLock, &windowDeadline) == ETIMEDOUT) {
            loge("Timed out waiting for renderer window acknowledgement");
            stopping = true;
            __atomic_store_n(&ldfaRendererInitState, LDFA_RENDERER_FAILED, __ATOMIC_RELEASE);
            pthread_cond_broadcast(&stateChangeFinishCond);
            if (stateCond)
                pthread_cond_signal(stateCond);
            break;
        }
    }
""",
    "window wait",
)
renderer = replace_once(
    renderer,
    """    if (eglSwapBuffers(egl_display, sfc) != EGL_TRUE)
        printEglError("Failed to swap buffers", __LINE__);

    // Perform a little drawing operation to make sure the next buffer is ready on the next invocation of drawing
""",
    """    EGLBoolean presented = eglSwapBuffers(egl_display, sfc);
    if (presented != EGL_TRUE) {
        printEglError("Failed to swap buffers", __LINE__);
        ldfaMarkRendererFailed(this, "eglSwapBuffers");
    } else {
        __atomic_add_fetch(&ldfaSuccessfulPresentSerial, 1, __ATOMIC_RELEASE);
        state->renderedFrames++;
    }

    // Teardown runs from the Activity lifecycle. Once stopping is requested,
    // do not enter the second fence wait just to pre-queue another buffer.
    if (stopping.load(std::memory_order_acquire))
        return;

    // Perform a little drawing operation to make sure the next buffer is ready on the next invocation of drawing
""",
    "successful swap counter",
)
renderer = replace_once(
    renderer,
    '''        eglClientWaitSyncKHR(egl_display, fence, 0, EGL_FOREVER);
        eglDestroySyncKHR(egl_display, fence);
        // Only now that the GPU has actually finished (not just been told to start) is it safe to
        // let present_execute_copy release/idle the source pixmap back to the client.
        state->gpuCopyQueue.completedSerial = serial;
        notifyGpuCopyDone();
''',
    '''        EGLint waitResult = fence == EGL_NO_SYNC_KHR
            ? EGL_FALSE
            : eglClientWaitSyncKHR(egl_display, fence, 0, LDFA_GPU_FENCE_TIMEOUT_NS);
        if (fence != EGL_NO_SYNC_KHR)
            eglDestroySyncKHR(egl_display, fence);
        if (waitResult == EGL_CONDITION_SATISFIED_KHR) {
            // Only now that the GPU has actually finished is it safe to release the source pixmap.
            state->gpuCopyQueue.completedSerial = serial;
            notifyGpuCopyDone();
        } else
            ldfaMarkRendererFailed(this, "standalone GPU copy fence");
''',
    "standalone GPU fence timeout",
)
renderer = replace_once(
    renderer,
    '''    // Wait until root window drawing is finished before giving control back to X server
    eglClientWaitSyncKHR(egl_display, fence, 0, EGL_FOREVER);
    eglDestroySyncKHR(egl_display, fence);
    if (gpuCopySerial) {
''',
    '''    // Wait until root window drawing is finished before giving control back to X server.
    EGLint rootWaitResult = fence == EGL_NO_SYNC_KHR
        ? EGL_FALSE
        : eglClientWaitSyncKHR(egl_display, fence, 0, LDFA_GPU_FENCE_TIMEOUT_NS);
    if (fence != EGL_NO_SYNC_KHR)
        eglDestroySyncKHR(egl_display, fence);
    if (rootWaitResult != EGL_CONDITION_SATISFIED_KHR) {
        ldfaMarkRendererFailed(this, "root-window GPU fence");
        lorie_mutex_unlock(&state->lock, &state->lockingPid);
        return;
    }
    if (gpuCopySerial) {
''',
    "root GPU fence timeout",
)
renderer = replace_once(
    renderer,
    '''    state->waitForNextFrame = true;
    lorie_mutex_unlock(&state->lock, &state->lockingPid);

    EGLBoolean presented = eglSwapBuffers(egl_display, sfc);
''',
    '''    state->waitForNextFrame = true;
    lorie_mutex_unlock(&state->lock, &state->lockingPid);

    // Do not begin another potentially blocking vendor EGL call once Activity
    // teardown is waiting for this renderer thread to exit.
    if (stopping.load(std::memory_order_acquire))
        return;

    EGLBoolean presented = eglSwapBuffers(egl_display, sfc);
''',
    "pre-swap teardown guard",
)
renderer = replace_once(
    renderer,
    '''    fence = eglCreateSyncKHR(egl_display, EGL_SYNC_FENCE_KHR, nullptr);
    eglClientWaitSyncKHR(egl_display, fence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, EGL_FOREVER);
    eglDestroySyncKHR(egl_display, fence);
''',
    '''    fence = eglCreateSyncKHR(egl_display, EGL_SYNC_FENCE_KHR, nullptr);
    EGLint prequeueWaitResult = fence == EGL_NO_SYNC_KHR
        ? EGL_FALSE
        : eglClientWaitSyncKHR(egl_display, fence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR,
                               LDFA_GPU_FENCE_TIMEOUT_NS);
    if (fence != EGL_NO_SYNC_KHR)
        eglDestroySyncKHR(egl_display, fence);
    if (prequeueWaitResult != EGL_CONDITION_SATISFIED_KHR)
        ldfaMarkRendererFailed(this, "next EGL buffer fence");
''',
    "next-buffer GPU fence timeout",
)
renderer = replace_once(renderer, "\n    state->renderedFrames++;\n}\n\nbool Renderer::shouldWait", "\n}\n\nbool Renderer::shouldWait", "obsolete FPS increment")
renderer = replace_once(
    renderer,
    """void Renderer::threadLoop() {
    LorieBuffer* buf;
""",
    """void Renderer::threadLoop() {
    // pthread_cond_wait and the temporary unlock below require ownership from
    // the very first iteration.
    pthread_mutex_lock(&stateLock);
    LorieBuffer* buf;
""",
    "threadLoop lock invariant",
)
renderer = replace_once(
    renderer,
    '''        if (windowChanged)
            refreshContext();

        // Attach all pending buffers to GL.
''',
    '''        if (windowChanged)
            refreshContext();
        if (stopping)
            break;

        // Attach all pending buffers to GL.
''',
    "runtime surface failure exit",
)
renderer = replace_once(
    renderer,
    '''    if (sfc != defaultSfc) // defensive; setWindow(nullptr) already reverts this before onDetachedFromWindow runs
        eglDestroySurface(egl_display, sfc);
    eglDestroySurface(egl_display, defaultSfc);
    eglDestroyContext(egl_display, ctx);
    // Intentionally not calling eglTerminate(egl_display): the EGLDisplay is a process-wide
    // driver connection, not a per-instance resource.
    ANativeWindow_release(defaultWin);
    munmap(stateCond, sizeof(pthread_cond_t));
    close(stateCondFd);
    jvm->DetachCurrentThread();
''',
    '''    if (sfc != EGL_NO_SURFACE && sfc != defaultSfc)
        eglDestroySurface(egl_display, sfc);
    if (defaultSfc != EGL_NO_SURFACE)
        eglDestroySurface(egl_display, defaultSfc);
    if (ctx != EGL_NO_CONTEXT)
        eglDestroyContext(egl_display, ctx);
    // Intentionally not calling eglTerminate(egl_display): the EGLDisplay is a process-wide
    // driver connection, not a per-instance resource.
    if (win && win != defaultWin)
        ANativeWindow_release(win);
    if (defaultWin)
        ANativeWindow_release(defaultWin);
    if (ldfaDefaultImageReader) {
        AImageReader_delete(ldfaDefaultImageReader);
        ldfaDefaultImageReader = nullptr;
    }
    if (lorieViewClass) {
        rendererEnv->DeleteGlobalRef(lorieViewClass);
        lorieViewClass = nullptr;
    }
    win = defaultWin = nullptr;
    sfc = defaultSfc = EGL_NO_SURFACE;
    ctx = EGL_NO_CONTEXT;
    egl_display = EGL_NO_DISPLAY;
    rendererEnv = nullptr;
    jvm->DetachCurrentThread();
''',
    "renderer thread-affine teardown",
)
(output / "renderer.cpp").write_text(renderer, encoding="utf-8")


# ---- Viewer JNI bridge ---------------------------------------------------
activity = activity_source.read_text(encoding="utf-8")
# Normalize the small local vendor diff so output is byte-for-byte reproducible
# from the pinned pristine submodule as well as the developer checkout.
activity = activity.replace(
    '    log(INFO, "LDFA: libXlorie JNI_OnLoad starting in process %s", __progname);\n',
    "",
    1,
)
activity = replace_once(
    activity,
    "JNIEXPORT jint JNI_OnLoad(JavaVM *vm, __unused void *reserved) {\n",
    '''JNIEXPORT jint JNI_OnLoad(JavaVM *vm, __unused void *reserved) {
    log(INFO, "LDFA: libXlorie JNI_OnLoad starting");
''',
    "JNI_OnLoad diagnostic",
)
activity = activity.replace(
    'if (!strcmp(__progname, "com.termux.x11"))',
    'if (!strcmp(__progname, "com.hatake716.linuxdesktop") || !strcmp(__progname, "com.hatake716.linuxdesktop:x11"))',
    1,
)
if 'if (!strcmp(__progname, "com.hatake716.linuxdesktop") || !strcmp(__progname, "com.hatake716.linuxdesktop:x11"))' not in activity:
    raise SystemExit("Termux:X11 stderr process guard changed; embedded patch needs review")
# A developer checkout briefly added __unused to three JNIEnv parameters while
# the pinned commit does not contain those annotations.  Normalize that
# cosmetic delta before applying the guarded safety patches so a pristine
# submodule and the local checkout produce byte-identical hardened JNI code.
for annotated, canonical in (
    ("+[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text)",
     "+[](JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text)"),
    ("+[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jfloat x, jfloat y, jint which_button, jboolean button_down, jboolean relative)",
     "+[](JNIEnv* env, __unused jobject cls, jlong ptr, jfloat x, jfloat y, jint which_button, jboolean button_down, jboolean relative)"),
    ("+[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jfloat x, jfloat y, jint pressure, jint tilt_x, jint tilt_y, jint orientation, jint buttons, jboolean eraser, jboolean mouse)",
     "+[](JNIEnv *env, __unused jobject thiz, jlong ptr, jfloat x, jfloat y, jint pressure, jint tilt_x, jint tilt_y, jint orientation, jint buttons, jboolean eraser, jboolean mouse)"),
):
    activity = activity.replace(annotated, canonical, 1)
activity = replace_once(
    activity,
    '#include "lorie.h"\n',
    '''#include "lorie.h"

extern "C" uint64_t ldfaGetSuccessfulPresentSerial();
extern "C" void ldfaResetSuccessfulPresentSerial();
extern "C" bool ldfaRendererIsReady();
extern "C" bool ldfaRendererAcceptsConnection();
''',
    "renderer health declarations in activity",
)
activity = replace_once(
    activity,
    "bool lorieDebugEnabled = false;\n",
    '''bool lorieDebugEnabled = false;
static volatile int ldfaViewerConnectionFd = -1;

extern "C" bool ldfaViewerConnectionAlive(void) {
    int fd = __atomic_load_n(&ldfaViewerConnectionFd, __ATOMIC_ACQUIRE);
    if (fd == -1)
        return false;

    // Duplicate before polling so a simultaneous shutdown cannot make us
    // inspect an unrelated descriptor that reused the same integer.
    int probeFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (probeFd == -1)
        return false;
    struct pollfd probe = {
        .fd = probeFd,
        .events = POLLIN | POLLHUP | POLLERR | POLLRDHUP,
    };
    bool alive = !(poll(&probe, 1, 0) == 1 &&
                   (probe.revents & (POLLERR | POLLNVAL | POLLRDHUP | POLLHUP)));
    close(probeFd);
    return alive;
}
''',
    "viewer connection health implementation",
)
activity = activity.replace(
    "static jboolean requestConnection(__unused jlong ptr)",
    "static jboolean requestConnection(__unused JNIEnv *env, __unused jclass clazz, __unused jlong ptr)",
    1,
)
activity = activity.replace(
    "static jboolean requestConnection(JNIEnv *env, jclass clazz, __unused jlong ptr)",
    "static jboolean requestConnection(__unused JNIEnv *env, __unused jclass clazz, __unused jlong ptr)",
    1,
)
if "static jboolean requestConnection(__unused JNIEnv *env, __unused jclass clazz, __unused jlong ptr)" not in activity:
    raise SystemExit("Termux:X11 requestConnection ABI changed; embedded patch needs review")
activity = activity.replace(
    "(void *) +[](jlong ptr) -> jboolean {",
    "(void *) +[](__unused JNIEnv* env, __unused jclass clazz, jlong ptr) -> jboolean {",
    1,
)
if "(void *) +[](__unused JNIEnv* env, __unused jclass clazz, jlong ptr) -> jboolean {" not in activity:
    raise SystemExit("Termux:X11 connected ABI changed; embedded patch needs review")
activity = activity.replace(
    "            // @CriticalNative: no implicit JNIEnv*/jclass, unlike the @FastNative entries below.\n",
    "            // LDFA uses the normal JNI ABI on every supported Android version.\n",
    1,
)
activity = replace_once(
    activity,
    '''            {"connect", "(JI)V", (void *) +[](__unused JNIEnv* env, __unused jclass clazz, jlong ptr, jint fd) {
                auto* r = (LorieViewResources*) ptr;
                if (!r) return;
                r->connect(fd);
            }},
''',
    '''            {"connect", "(JI)V", (void *) +[](__unused JNIEnv* env, __unused jclass clazz, jlong ptr, jint fd) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) {
                    if (fd >= 0)
                        close(fd);
                    return;
                }
                r->connect(fd);
            }},
''',
    "JNI connection fd ownership",
)
activity = replace_once(
    activity,
    '''            {"sendWindowChange", "(JIIILjava/lang/String;)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint width, jint height, jint framerate, jstring jname) {
''',
    '''            {"requestFullRedraw", "(J)V", (void *) +[](__unused JNIEnv* env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .type = EVENT_FULL_REDRAW);
            }},
            {"sendWindowChange", "(JIIILjava/lang/String;)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint width, jint height, jint framerate, jstring jname) {
''',
    "full redraw JNI bridge",
)
activity = replace_once(
    activity,
    '''    LorieViewResources(JNIEnv* callerEnv, jobject view);
    ~LorieViewResources();
    void connect(jint fd);
    int xcallback(int fd, int events);
''',
    '''    LorieViewResources(JNIEnv* callerEnv, jobject view);
    ~LorieViewResources();
    void disconnect();
    void connect(jint fd);
    int xcallback(int fd, int events);
''',
    "viewer disconnect declaration",
)
activity = replace_once(
    activity,
    '''LorieViewResources::~LorieViewResources() {
    destroyed = true;

    if (connFd != -1) {
        ALooper_removeFd(ALooper_forThread(), connFd);
        close(connFd);
        connFd = -1;
    }

    renderer.destroy();

    if (thiz) {
        env->DeleteGlobalRef(thiz);
        thiz = NULL;
    }
}
''',
    '''void LorieViewResources::disconnect() {
    int oldFd = __atomic_exchange_n(&connFd, -1, __ATOMIC_ACQ_REL);
    if (__atomic_load_n(&ldfaViewerConnectionFd, __ATOMIC_ACQUIRE) == oldFd)
        __atomic_store_n(&ldfaViewerConnectionFd, -1, __ATOMIC_RELEASE);
    if (oldFd != -1) {
        // Wake any renderer-side send and make queued callbacks harmless before
        // closing the descriptor.
        shutdown(oldFd, SHUT_RDWR);
        ALooper_removeFd(ALooper_forThread(), oldFd);
        close(oldFd);
        if (!destroyed) {
            renderer.setSharedState(nullptr);
            renderer.removeAllBuffers();
        }
        log(DEBUG, "disconnected");
    }
}

LorieViewResources::~LorieViewResources() {
    destroyed = true;
    disconnect();
    renderer.destroy();

    if (thiz) {
        env->DeleteGlobalRef(thiz);
        thiz = NULL;
    }
}
''',
    "viewer resource teardown",
)
activity = replace_once(
    activity,
    """    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        jobject instance = env->CallStaticObjectMethod(MainActivity.self, MainActivity.getInstance);
        if (instance)
            env->CallVoidMethod(instance, MainActivity.clientConnectedStateChanged);

        ALooper_removeFd(ALooper_forThread(), fd);
        close(connFd);
        connFd = -1;
        renderer.setSharedState(NULL);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
        return 1;
    }
""",
    """    if (fd != __atomic_load_n(&connFd, __ATOMIC_ACQUIRE)) {
        ALooper_removeFd(ALooper_forThread(), fd);
        return 0; // Any late callback from an older generation is stale.
    }

    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        disconnect();
        jobject instance = env->CallStaticObjectMethod(MainActivity.self, MainActivity.getInstance);
        if (instance)
            env->CallVoidMethod(instance, MainActivity.clientConnectedStateChanged);
        return 0;
    }
""",
    "viewer connection error handler",
)
activity = replace_once(
    activity,
    """void LorieViewResources::connect(jint fd) {
    if (connFd != -1) {
        ALooper_removeFd(ALooper_forThread(), connFd);
        close(connFd);
        renderer.setSharedState(NULL);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
    }

    if ((connFd = fd) != -1) {
        ALooper_addFd(ALooper_forThread(), fd, 0, ALOOPER_EVENT_INPUT | ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP,
                      +[](int fd, int events, void* data) -> int { return ((LorieViewResources*) data)->xcallback(fd, events); }, this);

        // Give the X server our renderer wakeup cond var fd, resent on every reconnect.
        lorieEvent e = { .type = EVENT_RENDERER_WAKEUP_COND };
        write(connFd, &e, sizeof(e));
        ancil_send_fd(connFd, renderer.getWakeupCondFd());

        log(DEBUG, "XCB connection is successfull");
    }
}
""",
    """void LorieViewResources::connect(jint fd) {
    disconnect();
    if (fd == -1)
        return;

    int wakeFd = renderer.getWakeupCondFd();
    if (destroyed || wakeFd < 0 || !ldfaRendererAcceptsConnection()) {
        close(fd);
        return;
    }

    if (ALooper_addFd(ALooper_forThread(), fd, 0,
                      ALOOPER_EVENT_INPUT | ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP,
                      +[](int callbackFd, int events, void* data) -> int {
                          return ((LorieViewResources*) data)->xcallback(callbackFd, events);
                      }, this) < 1) {
        close(fd);
        return;
    }

    __atomic_store_n(&connFd, fd, __ATOMIC_RELEASE);
    __atomic_store_n(&ldfaViewerConnectionFd, fd, __ATOMIC_RELEASE);
    ldfaResetSuccessfulPresentSerial();

    // Give the X server our renderer wakeup cond var fd, resent on every reconnect.
    lorieEvent e = { .type = EVENT_RENDERER_WAKEUP_COND };
    if (write(fd, &e, sizeof(e)) != sizeof(e) || ancil_send_fd(fd, wakeFd) != 0) {
        disconnect();
        return;
    }

    log(DEBUG, "XCB connection is successful");
}
""",
    "viewer connection lifecycle",
)
activity_health = r'''

extern "C" JNIEXPORT jlong JNICALL
Java_com_termux_x11_EmbeddedX11Display_nativeSuccessfulPresentSerial(
        __unused JNIEnv *env, __unused jclass clazz, jlong ptr) {
    auto *resources = (LorieViewResources *) (intptr_t) ptr;
    return (!resources || resources->destroyed)
        ? 0
        : (jlong) ldfaGetSuccessfulPresentSerial();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_x11_EmbeddedX11Display_nativeRendererReady(
        __unused JNIEnv *env, __unused jclass clazz, jlong ptr) {
    auto *resources = (LorieViewResources *) (intptr_t) ptr;
    return resources && !resources->destroyed && ldfaRendererIsReady();
}
'''
if "Java_com_termux_x11_EmbeddedX11Display_nativeSuccessfulPresentSerial" in activity:
    raise SystemExit("Successful-present accessor already exists upstream; embedded patch needs review")
activity += activity_health
(output / "activity.cpp").write_text(activity, encoding="utf-8")

print(f"Generated embedded native sources in {output}")
