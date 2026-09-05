package com.termux.shared.android;

import android.system.Os;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Read optional SELinux diagnostics without accessing hidden framework APIs. */
public class SELinuxUtils {
    @Nullable
    public static String getContext() {
        return readContext("/proc/self/attr/current");
    }

    @Nullable
    public static String getPidContext(int pid) {
        return pid > 0 ? readContext("/proc/" + pid + "/attr/current") : null;
    }

    @Nullable
    private static String readContext(String path) {
        try {
            return decodeContext(Files.readAllBytes(Paths.get(path)));
        } catch (Exception unavailable) {
            // Access is subject to the normal application UID and SELinux policy.
            return null;
        }
    }

    @Nullable
    public static String getFileContext(@NonNull String path) {
        try {
            return decodeContext(Os.getxattr(path, "security.selinux"));
        } catch (Exception unavailable) {
            return null;
        }
    }

    @Nullable
    private static String decodeContext(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? null : value;
    }
}
