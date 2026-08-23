#!/usr/bin/env python3
"""Generate the embedded Termux:X11 Java sources without editing the submodule.

The upstream viewer uses @CriticalNative for methods whose registered C functions do
not consistently use the critical-native ABI.  That is undefined on older Android
releases and can crash as soon as the viewer receives focus.  LDFA also uses a direct
Binder connection, so the upstream localhost:7892 reconnect path must not run.  The
embedded Activity is published only after initialization, so IME callbacks must never
capture its singleton while LorieView itself is still being inflated.
"""

from pathlib import Path
import shutil
import sys


if len(sys.argv) != 3:
    raise SystemExit("usage: prepare_embedded_java.py <upstream-java> <output-dir>")

upstream = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
lorie_source = upstream / "com" / "termux" / "x11" / "LorieView.java"
activity_source = upstream / "com" / "termux" / "x11" / "MainActivity.java"
if not lorie_source.is_file() or not activity_source.is_file():
    raise SystemExit("Pinned Termux:X11 Java sources are missing")

if output.exists():
    shutil.rmtree(output)
shutil.copytree(upstream, output)


def replace_once(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"Termux:X11 {description} changed; expected one match, found {count}"
        )
    return text.replace(old, new, 1)


# Normal JNI has one stable ABI on every supported Android version.  The current
# submodule checkout may already carry this local cleanup, so this normalization is
# deliberately idempotent while still rejecting any annotation left behind.
lorie_path = output / "com" / "termux" / "x11" / "LorieView.java"
lorie = lorie_path.read_text(encoding="utf-8")
lorie = lorie.replace("import dalvik.annotation.optimization.CriticalNative;\n", "")
lorie = lorie.replace("import dalvik.annotation.optimization.FastNative;\n", "")
lorie = lorie.replace("@CriticalNative ", "")
lorie = lorie.replace("@FastNative ", "")
if "CriticalNative" in lorie or "FastNative" in lorie:
    raise SystemExit("Termux:X11 native annotations changed; embedded patch needs review")
lorie = replace_once(
    lorie,
    "    private long mNativeContext;\n",
    """    private long mNativeContext;
    private boolean mNativeShutdown;
    private final Runnable mDeferredFullRedraw = () -> {
        long nativeContext = mNativeContext;
        Surface surface = getHolder().getSurface();
        if (!mNativeShutdown && nativeContext != 0 && isAttachedToWindow()
                && surface != null && surface.isValid())
            requestFullRedraw(nativeContext);
    };
""",
    "LorieView native ownership flag",
)

# MainActivity is intentionally published only after onCreate() has initialized the
# view, input handler and direct Binder transport.  Upstream captures the singleton in
# an anonymous InputConnection field while LorieView is inflated, permanently storing
# null in that ordering.  Gboard calls replaceText() as soon as composition starts and
# would then dereference the stale field.  Resolve the Activity at callback time and
# tolerate the narrow startup/teardown windows where it is not published.
lorie = replace_once(
    lorie,
    "        private final MainActivity a = MainActivity.getInstance();\n",
    "",
    "IME Activity capture",
)
lorie = replace_once(
    lorie,
    """            if (a.useTermuxEKBarBehaviour && a.mExtraKeys != null)
                a.mExtraKeys.unsetSpecialKeys();
""",
    """            MainActivity activity = MainActivity.getInstance();
            if (activity != null && activity.useTermuxEKBarBehaviour && activity.mExtraKeys != null)
                activity.mExtraKeys.unsetSpecialKeys();
""",
    "IME callback Activity lookup",
)
if "private final MainActivity a = MainActivity.getInstance()" in lorie:
    raise SystemExit("Termux:X11 IME still captures an unpublished MainActivity")

# A SurfaceView can detach and later reattach without its Activity being recreated. The
# SurfaceHolder callback already drops/reinstalls only the native Surface; destroying the
# renderer thread and Binder transport on every transient detach is both unnecessary and an
# ANR/resource-leak risk. Final ownership belongs to MainActivity.onDestroy().
lorie = replace_once(
    lorie,
    """    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        nativeDestroy(mNativeContext);
        mNativeContext = 0;
    }
""",
    """    // Transient detach is handled by surfaceDestroyed(); retain the renderer
    // context and X transport so reattachment does not rebuild EGL on the UI thread.
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    void shutdownNative() {
        if (mNativeShutdown)
            return;
        mNativeShutdown = true;
        removeCallbacks(mDeferredFullRedraw);
        long nativeContext = mNativeContext;
        mNativeContext = 0;
        getHolder().removeCallback(mSurfaceCallback);
        if (nativeContext != 0)
            nativeDestroy(nativeContext);
    }

    long getNativeContext() {
        return mNativeContext;
    }
""",
    "LorieView attach/detach lifecycle",
)
lorie = replace_once(
    lorie,
    """            Log.d("SurfaceChangedListener", "Surface was changed: " + width + "x" + height);
            updateViewport();
""",
    """            Log.d("SurfaceChangedListener", "Surface was changed: " + width + "x" + height);
            updateViewport();
            requestFullRedraw();
""",
    "Surface resume redraw",
)
lorie = replace_once(
    lorie,
    """    public void triggerCallback() {
        requestFocus();
        updateViewport();
    }
""",
    """    public void triggerCallback() {
        requestFocus();
        updateViewport();
    }

    /** Re-presents the intact X root pixmap after Android replaces this Surface. */
    public void requestFullRedraw() {
        removeCallbacks(mDeferredFullRedraw);
        mDeferredFullRedraw.run();
        // EGL/Surface setup is asynchronous and can be delayed under Chrome/Gboard
        // memory pressure. Retry only during the short resume window.
        postDelayed(mDeferredFullRedraw, 120);
        postDelayed(mDeferredFullRedraw, 400);
    }
""",
    "public full redraw request",
)
lorie = replace_once(
    lorie,
    """    private native void surfaceChanged(long ptr, Surface surface);
""",
    """    private native void surfaceChanged(long ptr, Surface surface);
    private native void requestFullRedraw(long ptr);
""",
    "full redraw native declaration",
)
lorie_path.write_text(lorie, encoding="utf-8")


# MainActivity is deliberately not published through its static singleton until
# onCreate() has completed.  Upstream TouchInputHandler used that singleton from
# its constructor, though, so the embedded build must use the Activity it already
# owns while initialization is still in progress.  Keep the public no-argument
# entry point for LorieView callbacks that run after Activity publication.
input_path = output / "com" / "termux" / "x11" / "input" / "TouchInputHandler.java"
input_handler = input_path.read_text(encoding="utf-8")
refresh_call_count = input_handler.count("refreshInputDevices();")
if refresh_call_count != 4:
    raise SystemExit(
        "Termux:X11 input-device refresh calls changed; "
        f"expected four matches, found {refresh_call_count}"
    )
input_handler = input_handler.replace(
    "refreshInputDevices();",
    "refreshInputDevices(mActivity);",
)
input_handler = replace_once(
    input_handler,
    """    static public void refreshInputDevices() {
        AtomicBoolean stylusAvailable = new AtomicBoolean(false);
""",
    """    static public void refreshInputDevices() {
        refreshInputDevices(MainActivity.getInstance());
    }

    private static void refreshInputDevices(MainActivity activity) {
        if (activity == null)
            return;
        AtomicBoolean stylusAvailable = new AtomicBoolean(false);
""",
    "input-device refresh Activity ownership",
)
input_handler = replace_once(
    input_handler,
    """        MainActivity.getInstance().getLorieView().requestStylusEnabled(stylusAvailable.get());
        MainActivity.getInstance().setExternalKeyboardConnected(externalKeyboardAvailable.get());
""",
    """        LorieView view = activity.getLorieView();
        if (view == null)
            return;
        view.requestStylusEnabled(stylusAvailable.get());
        activity.setExternalKeyboardConnected(externalKeyboardAvailable.get());
""",
    "input-device refresh singleton dereference",
)
input_path.write_text(input_handler, encoding="utf-8")


activity_path = output / "com" / "termux" / "x11" / "MainActivity.java"
activity = activity_path.read_text(encoding="utf-8")
activity = replace_once(
    activity,
    "import java.util.Map;\nimport java.util.Objects;\n",
    "import java.util.Map;\nimport java.util.NoSuchElementException;\nimport java.util.Objects;\n",
    "Binder unlink exception import",
)

activity = replace_once(
    activity,
    "    protected ICmdEntryInterface service = null;\n",
    """    protected volatile ICmdEntryInterface service = null;
    private final Object serviceConnectionLock = new Object();
    private IBinder serviceBinder = null;
    private IBinder.DeathRecipient serviceDeathRecipient = null;
    private final Runnable connectRetry = this::tryConnect;
    private final Runnable preferencesChangedCallback = this::onPreferencesChangedCallback;
    private final Runnable finishStartupDrawCallback = this::finishStartupDraw;
    private boolean destroying = false;
""",
    "service field",
)

activity = replace_once(
    activity,
    "    public static Prefs prefs = null;\n",
    """    public static Prefs prefs = null;
    private static final String LDFA_IME_RESIZE_MIGRATION = "ldfaImeResizeV1";
""",
    "IME resize migration marker",
)

# Do not publish a half-constructed Activity to EmbeddedX11Display.  In particular,
# getLorieView() is not valid until after setContentView and the rest of onCreate.
activity = replace_once(
    activity,
    """    public MainActivity() {
        instance = this;
    }
""",
    """    public MainActivity() {
        // Published at the end of onCreate, after LorieView is fully initialized.
    }
""",
    "activity publication",
)

# A startActivity request can remain queued after the repository has begun teardown.  Reject an
# Intent whose service generation was invalidated before Activity.onCreate reached the main thread.
activity = replace_once(
    activity,
    """        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
""",
    """        super.onCreate(savedInstanceState);
        if (!EmbeddedX11Display.isLaunchIntentAllowed(getIntent())) {
            finish();
            return;
        }

        prefs = new Prefs(this);
        // Cropping only the rendered viewport while an IME covers the Surface can
        // leave some Android GPU drivers presenting a black X11 frame.  Resize the
        // X screen to the visible area instead.  The one-time marker upgrades
        // existing installations while preserving the preference as an escape hatch.
        if (!prefs.get().getBoolean(LDFA_IME_RESIZE_MIGRATION, false)) {
            prefs.get().edit()
                    .putBoolean("Reseed", true)
                    .putBoolean(LDFA_IME_RESIZE_MIGRATION, true)
                    .commit();
            Log.i("MainActivity", "LDFA enabled IME-aware X11 resizing");
        }
""",
    "viewer launch generation guard",
)

# Consume the direct service Binder before the first connection attempt.  This removes
# the startup-time requestConnection() call to an intentionally absent TCP listener.
activity = replace_once(
    activity,
    """        if (tryConnect()) {
""",
    """        onReceiveConnection(getIntent());
        if (tryConnect()) {
""",
    "initial Binder ordering",
)
activity = replace_once(
    activity,
    """        onReceiveConnection(getIntent());
        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
""",
    """        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
        instance = this;
""",
    "late Binder receive",
)

activity = replace_once(
    activity,
    """        if (instance == this)
            instance = null;
        super.onDestroy();
""",
    """        handler.removeCallbacks(connectRetry);
        handler.removeCallbacks(preferencesChangedCallback);
        handler.removeCallbacks(finishStartupDrawCallback);
        if (prefs != null)
            prefs.get().unregisterOnSharedPreferenceChangeListener(preferencesChangedListener);
        clearServiceConnection(null);
        LorieView view = getLorieView();
        if (view != null)
            view.shutdownNative();
        EmbeddedX11Display.viewerDestroyed(getIntent());
        if (instance == this)
            instance = null;
        super.onDestroy();
""",
    "activity teardown",
)
activity = replace_once(
    activity,
    """        setTerminalToolbarView();
        getLorieView().requestFocus();
""",
    """        setTerminalToolbarView();
        getLorieView().requestFocus();
        getLorieView().requestFullRedraw();
""",
    "activity resume redraw",
)
activity = replace_once(
    activity,
    '''    protected void onDestroy() {
        handler.removeCallbacks(screenIdleTimeoutCheck);
''',
    '''    protected void onDestroy() {
        destroying = true;
        handler.removeCallbacks(screenIdleTimeoutCheck);
''',
    "activity destroying state",
)
activity = activity.replace(
    "handler.postDelayed(this::finishStartupDraw, 500)",
    "handler.postDelayed(finishStartupDrawCallback, 500)",
    1,
)
activity = activity.replace(
    "handler.removeCallbacks(this::onPreferencesChangedCallback)",
    "handler.removeCallbacks(preferencesChangedCallback)",
    1,
)
activity = activity.replace(
    "handler.postDelayed(this::onPreferencesChangedCallback, 100)",
    "handler.postDelayed(preferencesChangedCallback, 100)",
    1,
)
if (
    "postDelayed(this::finishStartupDraw" in activity
    or "removeCallbacks(this::onPreferencesChangedCallback" in activity
    or "postDelayed(this::onPreferencesChangedCallback" in activity
):
    raise SystemExit("Unstable MainActivity callback identity remains after generation")

old_connection = """    void onReceiveConnection(Intent intent) {
        Bundle bundle = intent == null ? null : intent.getBundleExtra(null);
        IBinder ibinder = bundle == null ? null : bundle.getBinder(null);
        if (ibinder == null)
            return;

        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            service.asBinder().linkToDeath(() -> {
                service = null;

                Log.v("Lorie", "Disconnected");
                runOnUiThread(() -> { getLorieView().connect(-1); clientConnectedStateChanged();} );
            }, 0);
        } catch (RemoteException ignored) {}

        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    getLorieView().startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent())
                    getIntent().putExtra(null, bundle);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    boolean tryConnect() {
        if (getLorieView().connected())
            return false;

        if (service == null) {
            boolean sent = getLorieView().requestConnection();
            handler.postDelayed(this::tryConnect, 250);
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                getLorieView().connect(fd.detachFd());
                finishStartupDraw();
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                handler.postDelayed(this::tryConnect, 250);
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            handler.postDelayed(this::tryConnect, 250);
        }
        return false;
    }
"""

new_connection = """    private void clearServiceConnection(IBinder expectedBinder) {
        synchronized (serviceConnectionLock) {
            if (expectedBinder != null && serviceBinder != expectedBinder)
                return;
            if (serviceBinder != null && serviceDeathRecipient != null) {
                try {
                    serviceBinder.unlinkToDeath(serviceDeathRecipient, 0);
                } catch (NoSuchElementException ignored) {}
            }
            service = null;
            serviceBinder = null;
            serviceDeathRecipient = null;
        }
    }

    void onReceiveConnection(Intent intent) {
        if (destroying)
            return;
        Bundle bundle = intent == null ? null : intent.getBundleExtra(null);
        IBinder binder = bundle == null ? null : bundle.getBinder(null);
        if (binder == null)
            return;

        ICmdEntryInterface nextService = ICmdEntryInterface.Stub.asInterface(binder);
        IBinder.DeathRecipient nextDeathRecipient = () -> handler.post(() -> {
            if (destroying)
                return;
            synchronized (serviceConnectionLock) {
                if (serviceBinder != binder)
                    return; // A late death from an older service must not tear down a new one.
                service = null;
                serviceBinder = null;
                serviceDeathRecipient = null;
            }
            Log.v("Lorie", "Disconnected");
            LorieView view = getLorieView();
            if (view != null)
                view.connect(-1);
            clientConnectedStateChanged();
        });

        synchronized (serviceConnectionLock) {
            if (serviceBinder != null && serviceDeathRecipient != null) {
                try {
                    serviceBinder.unlinkToDeath(serviceDeathRecipient, 0);
                } catch (NoSuchElementException ignored) {}
            }
            service = nextService;
            serviceBinder = binder;
            serviceDeathRecipient = nextDeathRecipient;
            try {
                binder.linkToDeath(nextDeathRecipient, 0);
            } catch (RemoteException exception) {
                service = null;
                serviceBinder = null;
                serviceDeathRecipient = null;
                return;
            }
        }

        try {
            if (binder.isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = nextService.getLogcatOutput();
                if (logcatOutput != null)
                    getLorieView().startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent())
                    getIntent().putExtra(null, bundle);
            }
        } catch (Exception exception) {
            clearServiceConnection(binder);
            Log.e("MainActivity", "Something went wrong while we were establishing connection", exception);
        }
    }

    boolean tryConnect() {
        if (destroying)
            return false;
        LorieView view = getLorieView();
        if (view == null || view.getNativeContext() == 0)
            return false;
        if (view.connected())
            return false;

        ICmdEntryInterface currentService = service;
        if (currentService == null)
            return true; // Direct Binder delivery owns reconnects; there is no TCP listener.

        try {
            ParcelFileDescriptor fd = currentService.getXConnection();
            if (fd != null) {
                if (destroying || view.getNativeContext() == 0) {
                    fd.close();
                    return false;
                }
                Log.v("MainActivity", "Extracting X connection socket.");
                view.connect(fd.detachFd());
                if (!view.connected()) {
                    handler.removeCallbacks(connectRetry);
                    handler.postDelayed(connectRetry, 250);
                    return true;
                }
                finishStartupDraw();
                view.triggerCallback();
                view.requestFullRedraw();
                clientConnectedStateChanged();
                view.reloadPreferences(prefs);
            } else {
                handler.removeCallbacks(connectRetry);
                handler.postDelayed(connectRetry, 250);
            }
        } catch (Exception exception) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", exception);
            clearServiceConnection(currentService.asBinder());
        }
        return false;
    }
"""
activity = replace_once(activity, old_connection, new_connection, "direct Binder connection block")

activity = replace_once(
    activity,
    """    public static boolean isConnected() {
        if (getInstance() == null)
            return false;

        return getInstance().getLorieView().connected();
    }
""",
    """    public static boolean isConnected() {
        MainActivity activity = getInstance();
        LorieView view = activity == null ? null : activity.getLorieView();
        return view != null && view.connected();
    }
""",
    "static connection check",
)

if "requestConnection();" in activity:
    raise SystemExit("Legacy Termux:X11 TCP reconnect call remains in generated MainActivity")
activity_path.write_text(activity, encoding="utf-8")

print(f"Generated embedded Java sources in {output}")
