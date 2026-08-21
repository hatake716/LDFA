package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.Field;

/**
 * Small public integration surface for the unified application.
 *
 * MainActivity remains upstream. Health checks are intentionally split into activity lifetime,
 * binder transport, Surface readiness and real rendered frames so the management layer can make
 * deterministic decisions instead of treating those states as equivalent.
 */
public final class EmbeddedX11Display {
    private EmbeddedX11Display() {}

    static {
        System.loadLibrary("Xlorie");
    }

    /** Brings an already-open viewer to the foreground without changing its connection. */
    public static void open(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    /** Opens a fresh viewer with the already-bound X11 service binder in its launch Intent. */
    public static void open(Context context, IBinder serviceBinder) {
        context.startActivity(createConnectionIntent(context, serviceBinder));
    }

    /**
     * Installs a fresh service binder into the existing viewer or opens a new viewer when needed.
     * This replaces the old TCP request + ACTION_START broadcast reconnect path.
     */
    public static void connect(Context context, IBinder serviceBinder) {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null) {
            open(context, serviceBinder);
            return;
        }

        activity.onReceiveConnection(createConnectionIntent(context, serviceBinder));
        open(context);
    }

    public static boolean isOpen() {
        return MainActivity.getInstance() != null;
    }

    public static boolean isTransportConnected() {
        return MainActivity.isConnected();
    }

    /** True only when the Android viewer can actually display a native X11 frame. */
    public static boolean isConnected() {
        return isTransportConnected() && isSurfaceReady() && renderedFrames() > 0;
    }

    public static boolean isSurfaceReady() {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null || activity.getLorieView() == null)
            return false;
        Surface surface = activity.getLorieView().getHolder().getSurface();
        return surface != null && surface.isValid();
    }

    public static int renderedFrames() {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null || activity.getLorieView() == null)
            return 0;
        try {
            Field nativeContext = LorieView.class.getDeclaredField("mNativeContext");
            nativeContext.setAccessible(true);
            long ptr = nativeContext.getLong(activity.getLorieView());
            return ptr == 0 ? 0 : nativeRenderedFrames(ptr);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 0;
        }
    }

    public static void close(Context context) {
        MainActivity activity = MainActivity.getInstance();
        if (activity != null)
            activity.finishAffinity();
    }

    private static Intent createConnectionIntent(Context context, IBinder serviceBinder) {
        Bundle bundle = new Bundle();
        bundle.putBinder(null, serviceBinder);
        return new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(null, bundle);
    }

    private static native int nativeRenderedFrames(long nativeContext);
}
