package com.termux.shared.android;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;


public class UserUtils {

    public static final String LOG_TAG = "UserUtils";

    /** Return the public package-manager name, or null if the UID has no visible package. */
    @Nullable
    public static String getNameForUid(@NonNull Context context, int uid) {
        return getNameForUidFromPackageManager(context, uid);
    }

    /**
     * Get the user name for user id with a call to {@link PackageManager#getNameForUid(int)}.
     *
     * System UID names may be unavailable; callers retain the numeric UID.
     *
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/content/pm/PackageManager.java;l=5556
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ApplicationPackageManager.java;l=1028
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java;l=10293
     *
     * @param context The {@link Context} for operations.
     * @param uid The user id.
     * @return Returns the user name if found, otherwise {@code null}.
     */
    @Nullable
    public static String getNameForUidFromPackageManager(@NonNull Context context, int uid) {
        if (uid < 0) return null;

        try {
            String name = context.getPackageManager().getNameForUid(uid);
            if (name != null && name.endsWith(":" + uid))
                name = name.replaceAll(":" + uid + "$", ""); // Remove ":<uid>" suffix
            return name;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get name for uid \"" + uid + "\" from package manager", e);
            return null;
        }
    }

}
