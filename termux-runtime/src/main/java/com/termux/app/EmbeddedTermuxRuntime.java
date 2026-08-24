package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stable integration surface used by the Linux Desktop Compose application.
 * The implementation delegates to Termux's own bootstrap installer and terminal activity.
 */
public final class EmbeddedTermuxRuntime {
    private static final String LOG_TAG = "EmbeddedTermuxRuntime";
    private static final String INTERNAL_COMMAND_POLICY = "allow-external-apps=true";
    private static final Pattern INTERNAL_COMMAND_POLICY_PATTERN = Pattern.compile(
        "^\\s*#?\\s*allow-external-apps\\s*=.*$"
    );

    private EmbeddedTermuxRuntime() {}

    public static boolean isBootstrapInstalled() {
        return FileUtils.directoryFileExists(TermuxConstants.TERMUX_PREFIX_DIR_PATH, true) &&
            !com.termux.shared.termux.file.TermuxFileUtils.isTermuxPrefixDirectoryEmpty();
    }

    public static void setupBootstrapIfNeeded(Activity activity, Runnable whenDone) {
        TermuxInstaller.setupBootstrapIfNeeded(activity, () -> {
            if (!ensureInternalCommandPolicy(activity)) {
                Log.e(LOG_TAG, "Failed to prepare the internal RunCommand policy");
            }
            whenDone.run();
        });
    }

    /**
     * Enables RunCommandService for calls made inside this single APK.
     *
     * Termux's upstream RunCommandService applies the allow-external-apps policy even when the
     * caller and service are part of the same installed package. The service itself remains
     * non-exported in this application, so enabling the property does not expose command execution
     * to other Android applications.
     */
    public static synchronized boolean ensureInternalCommandPolicy(Context context) {
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties == null) {
            properties = TermuxAppSharedProperties.init(context.getApplicationContext());
        }
        if (properties.shouldAllowExternalApps()) return true;

        File propertiesFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
        File parent = propertiesFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            Log.e(LOG_TAG, "Failed to create Termux properties directory: " + parent);
            return false;
        }

        List<String> lines = new ArrayList<>();
        boolean policyPresent = false;
        boolean rewriteRequired = false;
        if (propertiesFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(propertiesFile), StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (INTERNAL_COMMAND_POLICY_PATTERN.matcher(line).matches()) {
                        if (!policyPresent) {
                            lines.add(INTERNAL_COMMAND_POLICY);
                            policyPresent = true;
                            if (!INTERNAL_COMMAND_POLICY.equals(line)) rewriteRequired = true;
                        } else {
                            // Remove duplicate or conflicting declarations so parsing order cannot
                            // disable the private in-app command channel later in the file.
                            rewriteRequired = true;
                        }
                    } else {
                        lines.add(line);
                    }
                }
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Failed to read Termux properties", exception);
                return false;
            }
        }

        if (!policyPresent) {
            lines.add(INTERNAL_COMMAND_POLICY);
            rewriteRequired = true;
        }

        if (rewriteRequired) {
            File temporaryFile = new File(parent, propertiesFile.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporaryFile, false);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     output, StandardCharsets.UTF_8
                 ))) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
                writer.flush();
                output.getFD().sync();
            } catch (IOException exception) {
                Log.e(LOG_TAG, "Failed to write Termux properties", exception);
                temporaryFile.delete();
                return false;
            }

            if (propertiesFile.exists() && !propertiesFile.delete()) {
                Log.e(LOG_TAG, "Failed to replace existing Termux properties");
                temporaryFile.delete();
                return false;
            }
            if (!temporaryFile.renameTo(propertiesFile)) {
                Log.e(LOG_TAG, "Failed to install Termux properties atomically");
                temporaryFile.delete();
                return false;
            }
        }

        // TermuxApplication initializes a cached properties object before the bootstrap and this
        // file may not exist at that time. Reload it explicitly so the next RunCommandService call
        // sees the policy immediately without requiring an app restart.
        properties.loadTermuxPropertiesFromDisk();
        return properties.shouldAllowExternalApps();
    }

    /**
     * Creates Termux's ~/storage/shared link after the unified app receives Android storage access.
     * This replaces the external Termux application's termux-setup-storage interaction and avoids a
     * setup deadlock where the host bootstrap waits for a link that no other application can create.
     */
    public static synchronized boolean ensureSharedStorageLink() {
        File storageHome = TermuxConstants.TERMUX_STORAGE_HOME_DIR;
        File sharedLink = new File(storageHome, "shared");
        File externalStorage = Environment.getExternalStorageDirectory();

        if (!externalStorage.isDirectory()) {
            Log.e(LOG_TAG, "Android shared storage is not available: " + externalStorage);
            return false;
        }
        if (!storageHome.isDirectory() && !storageHome.mkdirs()) {
            Log.e(LOG_TAG, "Failed to create Termux storage directory: " + storageHome);
            return false;
        }
        if (sharedLink.isDirectory()) return true;

        // File.exists() is false for a dangling symlink, so use the Termux helper as a second check.
        if (FileUtils.fileExists(sharedLink.getAbsolutePath(), false)) {
            if (!sharedLink.delete()) {
                Log.e(LOG_TAG, "Failed to remove stale shared storage entry: " + sharedLink);
                return false;
            }
        }

        try {
            Os.symlink(externalStorage.getAbsolutePath(), sharedLink.getAbsolutePath());
        } catch (ErrnoException exception) {
            Log.e(LOG_TAG, "Failed to create shared storage symlink", exception);
            return false;
        }

        // The symlink was created; report success based on that rather than on
        // sharedLink.isDirectory(). isDirectory() follows the link and stat()s
        // /storage/emulated/0, which can momentarily fail (FUSE mount racing the
        // storage grant) even though the link is correct — matching the shell
        // doctor's link-existence semantics keeps setup item 3 from latching.
        return true;
    }

    public static void openTerminal(Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
