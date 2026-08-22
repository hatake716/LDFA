package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;

/**
 * Small public integration surface for the unified application.
 *
 * MainActivity remains upstream. Health checks are intentionally split into activity lifetime,
 * binder transport, renderer readiness, Surface readiness and successful presentation so the management layer can make
 * deterministic decisions instead of treating those states as equivalent.
 */
public final class EmbeddedX11Display {
    private static final String EXTRA_LAUNCH_GENERATION =
        "com.hatake716.linuxdesktop.extra.X11_VIEWER_GENERATION";
    private static final Object launchLock = new Object();
    private static String allowedLaunchGeneration;

    private EmbeddedX11Display() {}

    static {
        System.loadLibrary("Xlorie");
    }

    /** Brings an already-open viewer to the foreground without changing its connection. */
    public static void open(Context context) {
        if (MainActivity.getInstance() == null)
            return;
        Intent intent = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    /** Opens a fresh viewer with the already-bound X11 service binder in its launch Intent. */
    private static void open(Context context, IBinder serviceBinder, String generation) {
        context.startActivity(createConnectionIntent(context, serviceBinder, generation));
    }

    /**
     * Installs a fresh service binder into the existing viewer or opens a new viewer when needed.
     * This replaces the old TCP request + ACTION_START broadcast reconnect path.
     */
    public static void connect(Context context, IBinder serviceBinder, String generation) {
        if (generation == null || generation.isEmpty())
            throw new IllegalArgumentException("X11 viewer generation is required");
        synchronized (launchLock) {
            allowedLaunchGeneration = generation;
        }

        MainActivity activity = MainActivity.getInstance();
        if (activity == null) {
            open(context, serviceBinder, generation);
            return;
        }

        Intent connectionIntent = createConnectionIntent(context, serviceBinder, generation);
        activity.setIntent(connectionIntent);
        activity.onReceiveConnection(connectionIntent);
        open(context);
    }

    public static boolean isOpen() {
        return MainActivity.getInstance() != null;
    }

    /**
     * True only while the viewer owns the focused Android window. A valid Activity may remain
     * alive after Home/Recents removes its Surface, which is normal and must not trigger Xorg
     * recovery from the background heartbeat.
     */
    public static boolean isViewerForeground() {
        MainActivity activity = MainActivity.getInstance();
        return activity != null && activity.hasWindowFocus();
    }

    public static boolean isTransportConnected() {
        return MainActivity.isConnected();
    }

    /** True only after this viewer connection has successfully presented at least one frame. */
    public static boolean isConnected() {
        return isViewerReady() && successfulPresentSerial() > 0;
    }

    /** The Binder, LorieView, Android Surface and EGL renderer are all ready for a draw probe. */
    public static boolean isViewerReady() {
        return isTransportConnected() && isSurfaceReady() && rendererReady();
    }

    public static boolean isSurfaceReady() {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null || activity.getLorieView() == null)
            return false;
        Surface surface = activity.getLorieView().getHolder().getSurface();
        return surface != null && surface.isValid();
    }

    /** Monotonic for the current viewer connection; incremented only after EGL_TRUE presentation. */
    public static long successfulPresentSerial() {
        long ptr = nativeContext();
        return ptr == 0 ? 0 : nativeSuccessfulPresentSerial(ptr);
    }

    public static boolean rendererReady() {
        long ptr = nativeContext();
        return ptr != 0 && nativeRendererReady(ptr);
    }

    private static long nativeContext() {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null)
            return 0;
        LorieView view = activity.getLorieView();
        if (view == null)
            return 0;

        return view.getNativeContext();
    }

    public static void close(Context context) {
        synchronized (launchLock) {
            allowedLaunchGeneration = null;
        }
        MainActivity activity = MainActivity.getInstance();
        if (activity != null)
            activity.finishAffinity();
    }

    public static boolean isLaunchIntentAllowed(Intent intent) {
        String generation = intent == null ? null : intent.getStringExtra(EXTRA_LAUNCH_GENERATION);
        synchronized (launchLock) {
            return generation != null && generation.equals(allowedLaunchGeneration);
        }
    }

    public static void viewerDestroyed(Intent intent) {
        String generation = intent == null ? null : intent.getStringExtra(EXTRA_LAUNCH_GENERATION);
        synchronized (launchLock) {
            if (generation != null && generation.equals(allowedLaunchGeneration))
                allowedLaunchGeneration = null;
        }
    }

    private static Intent createConnectionIntent(
        Context context,
        IBinder serviceBinder,
        String generation
    ) {
        Bundle bundle = new Bundle();
        bundle.putBinder(null, serviceBinder);
        return new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_LAUNCH_GENERATION, generation)
            .putExtra(null, bundle);
    }

    private static native long nativeSuccessfulPresentSerial(long nativeContext);
    private static native boolean nativeRendererReady(long nativeContext);
}
