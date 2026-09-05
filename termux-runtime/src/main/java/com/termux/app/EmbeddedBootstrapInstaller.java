package com.termux.app;

import android.content.Context;
import android.system.Os;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the APK's bootstrap without retaining an Activity or showing a blocking dialog. */
public final class EmbeddedBootstrapInstaller {
    private EmbeddedBootstrapInstaller() {}

    public static synchronized void install(Context context) throws Exception {
        if (EmbeddedTermuxRuntime.isBootstrapInstalled()) {
            if (!EmbeddedTermuxRuntime.ensureInternalCommandPolicy(context))
                throw new IOException("内蔵環境の実行設定を保存できませんでした。");
            return;
        }
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        // An incomplete user-managed prefix must never be overwritten by an automatic retry.
        if (prefix.exists() && !TermuxFileUtils.isTermuxPrefixDirectoryEmpty())
            throw new IOException("内蔵環境を確認できません。既存のファイルを保護するため準備を中断しました。");
        File staging = TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
        if (FileUtils.deleteFile("bootstrap staging", staging.getPath(), true) != null)
            throw new IOException("一時ファイルを整理できませんでした。");
        directory(staging);
        directory(TermuxConstants.TERMUX_HOME_DIR);
        List<String[]> links = new ArrayList<>();
        byte[] buffer = new byte[32768];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(TermuxInstaller.loadZipBytes()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                if (entry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("←", 2);
                        if (parts.length != 2) throw new IOException("Invalid bootstrap symlink");
                        File link = child(staging, parts[1]);
                        directory(link.getParentFile());
                        links.add(new String[]{parts[0], link.getPath()});
                    }
                } else {
                    File target = child(staging, entry.getName());
                    directory(entry.isDirectory() ? target : target.getParentFile());
                    if (!entry.isDirectory()) {
                        try (FileOutputStream output = new FileOutputStream(target)) {
                            int count;
                            while ((count = zip.read(buffer)) != -1) {
                                checkInterrupted();
                                output.write(buffer, 0, count);
                            }
                        }
                        String name = entry.getName();
                        if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods/"))
                            Os.chmod(target.getPath(), 0700);
                    }
                }
            }
        }
        if (links.isEmpty() || !new File(staging, "bin/bash").isFile())
            throw new IOException("APKの実行環境を確認できませんでした。");
        for (String[] link : links) {
            checkInterrupted();
            Os.symlink(link[0], link[1]);
        }
        // Only the known empty prefix is removed, after successful extraction. home is preserved.
        if (prefix.exists() && (!TermuxFileUtils.isTermuxPrefixDirectoryEmpty() ||
            FileUtils.deleteFile("empty prefix", prefix.getPath(), true) != null))
            throw new IOException("実行環境の保存先が変更されました。もう一度お試しください。");
        if (!staging.renameTo(prefix)) throw new IOException("実行環境を保存できませんでした。");
        TermuxShellEnvironment.writeEnvironmentToFile(context);
        if (!EmbeddedTermuxRuntime.ensureInternalCommandPolicy(context))
            throw new IOException("内蔵環境の実行設定を保存できませんでした。");
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Bootstrap cancelled");
    }

    private static File child(File root, String name) throws IOException {
        File file = new File(root, name);
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Unsafe bootstrap path");
        return file;
    }

    private static void directory(File dir) throws IOException {
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs()))
            throw new IOException("Cannot create bootstrap directory");
    }
}
