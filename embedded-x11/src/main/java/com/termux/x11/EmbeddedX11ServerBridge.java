package com.termux.x11;

import android.os.ParcelFileDescriptor;

/**
 * Thin JNI facade used by the dedicated Android X11 service process.
 *
 * Unlike CmdEntryPoint this class has no shell/app_process lifecycle assumptions. It only exposes
 * the already-built native Xorg entry points to a normal Android Service process.
 */
public final class EmbeddedX11ServerBridge {
    private EmbeddedX11ServerBridge() {}

    static {
        System.loadLibrary("Xlorie");
    }

    public static native boolean start(String[] args);
    public static native ParcelFileDescriptor getXConnection();
    public static native boolean connected();
}
