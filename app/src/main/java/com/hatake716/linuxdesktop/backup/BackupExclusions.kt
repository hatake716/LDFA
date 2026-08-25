package com.hatake716.linuxdesktop.backup

/**
 * Decides which guest paths a FULL backup skips (docs/… §5.5). Pure logic with no
 * Android or filesystem dependency, so it is fully unit-testable.
 *
 * Paths are matched as guest-absolute paths from the rootfs root, e.g. `/proc` or
 * `/home/desktop/.cache/foo`. The most important rule is the self-recursion guard:
 * when the backup is written INTO the shared folder that itself lives under the
 * rootfs walk, reading the growing output file must be prevented — that is what
 * [outputRealSubtree] does, matched against the host-absolute path.
 */
class BackupExclusions(
    private val outputRealSubtree: String? = null,
) {
    /**
     * @param guestPath path as seen inside the guest, `/`-rooted (e.g. `/proc`).
     * @param realPath the matching host-absolute path on disk, used only for the
     *   output self-recursion guard. Pass null when it is not known/relevant.
     */
    fun isExcluded(guestPath: String, realPath: String? = null): Boolean {
        val p = normalize(guestPath)

        // 1. Whole subtrees recreated (empty) or re-fetchable on restore.
        for (dir in EXACT_DIR_SUBTREES) {
            if (p == dir || p.startsWith("$dir/")) return true
        }

        // 2. Chrome cache dirs under any profile (profile name varies).
        if (p.startsWith(CHROME_ROOT + "/")) {
            for (suffix in CHROME_CACHE_SUFFIXES) {
                if (p.contains("/$suffix/") || p.endsWith("/$suffix")) return true
            }
        }

        // 3. Chrome Singleton lock/socket/cookie anywhere (encodes source host+pid).
        val base = p.substringAfterLast('/')
        if (base in SINGLETON_NAMES) return true

        // 4. Rotated / compressed logs that need not migrate.
        if (p.startsWith("/var/log/") &&
            (p.endsWith(".gz") || p.endsWith(".xz") || ROTATED_LOG.containsMatchIn(p))
        ) {
            return true
        }

        // 5. Self-recursion guard: never read the output file's own subtree.
        if (realPath != null && outputRealSubtree != null) {
            val rp = normalize(realPath)
            val root = normalize(outputRealSubtree)
            if (rp == root || rp.startsWith("$root/")) return true
        }

        return false
    }

    /** The exact guest-absolute list this instance drops, for the manifest's `excludes`. */
    fun manifestExcludes(): List<String> = buildList {
        addAll(EXACT_DIR_SUBTREES.map { "$it/*" })
        add("$CHROME_ROOT/*/{Cache,Code Cache,GPUCache,Service Worker/CacheStorage}")
        addAll(SINGLETON_NAMES.map { "**/$it" })
        add("/var/log/**/*.{gz,xz}")
    }

    companion object {
        private const val CHROME_ROOT = "/home/desktop/.config/google-chrome"
        private val ROTATED_LOG = Regex("\\.\\d+$")

        /** Directories whose entire subtree is dropped. */
        private val EXACT_DIR_SUBTREES = listOf(
            "/proc", "/sys", "/dev",
            "/tmp", "/run", "/var/run",
            "/mnt/android",
            "/var/cache/apt/archives",
            "/var/lib/apt/lists",
            "/home/desktop/.npm/_cacache",
            "/home/desktop/.cache",
            "/root/.cache",
            "/home/desktop/.local/share/linux-desktop-for-android/logs",
        )

        private val CHROME_CACHE_SUFFIXES = listOf(
            "Cache", "Code Cache", "GPUCache", "Service Worker/CacheStorage",
        )

        private val SINGLETON_NAMES = setOf(
            "SingletonLock", "SingletonSocket", "SingletonCookie",
        )

        /** Normalize to a `/`-rooted path with single slashes and no trailing slash. */
        fun normalize(path: String): String {
            var p = path.replace('\\', '/')
            if (!p.startsWith("/")) p = "/$p"
            p = p.replace(Regex("/+"), "/")
            if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
            return p
        }
    }
}
