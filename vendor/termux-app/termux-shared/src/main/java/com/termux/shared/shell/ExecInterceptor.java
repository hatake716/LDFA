package com.termux.shared.shell;

/**
 * A single, optional rewrite hook applied to a command right before it is exec'd by
 * {@code AppShell}. LDFA registers a rewriter here to run data-dir binaries through
 * a native-library proot on targetSdk >= 29 (Google Play W^X compliance); when no
 * rewriter is registered (the default, and every upstream Termux use), the command
 * and environment pass through unchanged, so existing behavior is untouched.
 *
 * Kept in termux-shared with no app dependency: the app injects its logic via
 * {@link #setRewriter(Rewriter)} at startup.
 */
public final class ExecInterceptor {

    /** Rewrites the exec command and/or its environment. Return the value to use. */
    public interface Rewriter {
        /** @return the command array to exec (may be the same array). */
        String[] rewriteCommand(String[] command, String workingDirectory);
        /** @return the environment array to use (may be the same array). */
        String[] rewriteEnvironment(String[] environment, String[] originalCommand);
    }

    private static volatile Rewriter sRewriter;

    private ExecInterceptor() {}

    public static void setRewriter(Rewriter rewriter) {
        sRewriter = rewriter;
    }

    public static String[] rewriteCommand(String[] command, String workingDirectory) {
        Rewriter r = sRewriter;
        return r == null ? command : r.rewriteCommand(command, workingDirectory);
    }

    public static String[] rewriteEnvironment(String[] environment, String[] originalCommand) {
        Rewriter r = sRewriter;
        return r == null ? environment : r.rewriteEnvironment(environment, originalCommand);
    }
}
