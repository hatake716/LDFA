package com.hatake716.linuxdesktop.runtime

import com.termux.shared.shell.ExecInterceptor
import java.io.File

/**
 * Registers, into termux-shared's [ExecInterceptor], a rewriter that runs each
 * data-dir command through the native-library proot (see [ProotRuntime]). This is
 * how a targetSdk >= 29 / Google Play build executes the bootstrap's bash/apt and
 * proot-distro's Debian despite Android's W^X restriction on data-dir binaries.
 *
 * Registration is a no-op-in-effect when the proot native libs are absent (legacy
 * targetSdk-28 builds): [ProotRuntime.available] is false, so [wrap] returns the
 * command unchanged and the environment additions are empty.
 */
object ProotExecRewriter {

    /** Guest path the bootstrap ELFs hard-code as their RUNPATH. */
    private const val TERMUX_LIB = "/data/data/com.hatake716.linuxdesktop/files/usr/lib"

    fun register(runtime: ProotRuntime, termuxLibDir: File) {
        if (!runtime.available) return
        ExecInterceptor.setRewriter(object : ExecInterceptor.Rewriter {
            override fun rewriteCommand(command: Array<String>, workingDirectory: String?): Array<String> {
                if (command.isEmpty()) return command
                // Never wrap proot with proot (that is the wrapper itself).
                val exe = command[0]
                if (exe.endsWith("/libpdrt.so") || exe.endsWith("/proot")) return command
                // Bind the real Termux lib dir onto the RUNPATH the guest ELFs expect,
                // so their shared libraries resolve inside proot.
                val binds = if (termuxLibDir.isDirectory) {
                    listOf("${termuxLibDir.absolutePath}:$TERMUX_LIB")
                } else {
                    emptyList()
                }
                val wrapped = runtime.wrap(command.toList(), rootfs = null, binds = binds)
                return wrapped.toTypedArray()
            }

            override fun rewriteEnvironment(environment: Array<String>, originalCommand: Array<String>): Array<String> {
                if (originalCommand.isNotEmpty()) {
                    val exe = originalCommand[0]
                    if (exe.endsWith("/libpdrt.so") || exe.endsWith("/proot")) return environment
                }
                val additions = runtime.environment()
                if (additions.isEmpty()) return environment
                // Merge: keep existing entries, override/append proot's.
                val merged = LinkedHashMap<String, String>()
                for (entry in environment) {
                    val eq = entry.indexOf('=')
                    if (eq >= 0) merged[entry.substring(0, eq)] = entry.substring(eq + 1) else merged[entry] = ""
                }
                merged.putAll(additions)
                return merged.map { "${it.key}=${it.value}" }.toTypedArray()
            }
        })
    }
}
