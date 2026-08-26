package com.hatake716.linuxdesktop.runtime

import android.content.Context
import java.io.File

/**
 * Runs the Termux/Debian runtime under a proot that ships as a NATIVE LIBRARY.
 *
 * Why: from Android 10 (API 29) on, a binary written to the app data dir cannot be
 * execve'd (SELinux `execute_no_trans` on `app_data_file`). That is the wall that
 * kept Termux — and therefore LDFA — off Google Play. The bootstrap's bash/apt and
 * the whole Debian rootfs live in the data dir, so a targetSdk >= 29 build cannot
 * execute them directly.
 *
 * Fix (validated on real hardware, see the PoCs): ship `proot` + its loader + deps
 * as native libraries in `nativeLibraryDir` (which stays executable) and run every
 * data-dir binary THROUGH that proot. proot injects its loader and maps the guest
 * binary itself, sidestepping the execve restriction. proot-distro's own inner
 * proot nests fine as long as both levels share the one native loader.
 *
 * This class is a thin, side-effect-free command transformer. It is GATED: when the
 * proot native libs are absent (e.g. legacy targetSdk-28 builds that still execve
 * from the data dir), [wrap] returns the command unchanged so existing behavior is
 * untouched. Only when the libs are present does it prepend the proot invocation.
 */
class ProotRuntime internal constructor(
    private val nativeLibDir: File,
    private val prootTmpDir: File,
) {
    private val proot = File(nativeLibDir, LIB_PROOT)
    private val loader = File(nativeLibDir, LIB_PROOT_LOADER)

    /** True when the proot native libs are present and executable. */
    val available: Boolean
        get() = proot.canExecute() && loader.canExecute()

    /**
     * Transform [command] so its first element (a data-dir executable) runs under the
     * native proot. Returns [command] unchanged when proot is not [available].
     *
     * [rootfs] optionally sets the proot guest root (`-r`); null keeps the host FS.
     * [binds] are extra `host:guest` bind mounts. The guest lib dir must be bound
     * onto the RUNPATH the guest ELFs expect (Termux prefix `…/usr/lib`) so their
     * shared libraries resolve; callers pass that in [binds].
     */
    fun wrap(
        command: List<String>,
        rootfs: File? = null,
        binds: List<String> = emptyList(),
        killOnExit: Boolean = true,
    ): List<String> {
        if (!available || command.isEmpty()) return command
        val out = ArrayList<String>(command.size + binds.size * 2 + 6)
        out += proot.absolutePath
        // --kill-on-exit reaps all tracees when THIS proot exits. That is right for
        // short-lived commands, but WRONG for the desktop worker: proot kills its
        // tracees on exit and setsid cannot escape a proot's lifetime, so a worker
        // must run under its OWN persistent proot with kill-on-exit OFF and be kept
        // alive by the app (DesktopKeepAliveService).
        if (killOnExit) out += "--kill-on-exit"
        if (rootfs != null) { out += "-r"; out += rootfs.absolutePath }
        for (bind in binds) { out += "-b"; out += bind }
        out += command
        return out
    }

    /**
     * Environment additions proot needs. Merge into the child's environment (do not
     * replace it). PROOT_LOADER points at the native loader — the same one satisfies
     * a nested inner proot too. LD_LIBRARY_PATH lets proot's own NEEDED libs resolve
     * from nativeLibDir.
     */
    fun environment(): Map<String, String> {
        if (!available) return emptyMap()
        prootTmpDir.mkdirs()
        return mapOf(
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
            // Tells ldfa-host.sh to use its setsid+PID worker backend instead of
            // tmux (tmux's server re-exec escapes proot and dies under W^X).
            "LDFA_NATIVE_PROOT" to "1",
        )
    }

    companion object {
        // NB: the file name must NOT contain "proot". proot-distro refuses to run when
        // its ptrace tracer's /proc/<pid>/status Name contains "proot" (its nested-proot
        // guard). The tracer here IS our native proot, so its comm — the .so basename —
        // must be proot-free. "pdrt" = proot-distro runtime.
        private const val LIB_PROOT = "libpdrt.so"
        private const val LIB_PROOT_LOADER = "libpdrt-loader.so"

        fun from(context: Context): ProotRuntime {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val tmp = File(context.filesDir, "proot-tmp")
            return ProotRuntime(nativeLibDir, tmp)
        }
    }
}
