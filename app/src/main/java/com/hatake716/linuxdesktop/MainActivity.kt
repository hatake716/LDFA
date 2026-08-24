package com.hatake716.linuxdesktop

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.hatake716.linuxdesktop.data.ContainerInfo
import com.hatake716.linuxdesktop.ui.LinuxDesktopRoot
import com.hatake716.linuxdesktop.ui.MainViewModel
import com.hatake716.linuxdesktop.ui.theme.LinuxDesktopTheme
import com.termux.app.EmbeddedTermuxRuntime

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingStartContainer: ContainerInfo? = null
    private var bootstrapDialogRequested = false

    // Whether the WRITE_EXTERNAL_STORAGE runtime dialog has been shown at least once.
    // Combined with shouldShowRequestPermissionRationale(), this distinguishes a
    // first-ever request (dialog should appear) from a permanent denial (send the
    // user to app settings instead of silently doing nothing).
    private var hasRequestedStorageBefore = false

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) finalizeSharedStorageSetup() else viewModel.refreshEnvironment()
    }

    private val storageSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Returning from the app details settings: if storage is now permitted,
        // (re)establish the shared-storage link; otherwise just refresh the state.
        if (hasSharedStoragePermission()) {
            finalizeSharedStorageSetup()
        } else {
            viewModel.refreshEnvironment()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingStartContainer?.let(viewModel::startContainer)
        pendingStartContainer = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LinuxDesktopTheme {
                LinuxDesktopRoot(
                    viewModel = viewModel,
                    onPrepareRuntime = ::prepareEmbeddedRuntime,
                    onGrantStorageAccess = ::requestStorageAccess,
                    onStartContainer = ::startContainerWithNotificationPermission,
                )
            }
        }

        if (!EmbeddedTermuxRuntime.isBootstrapInstalled()) {
            prepareEmbeddedRuntime()
        }
    }

    override fun onResume() {
        super.onResume()
        if (EmbeddedTermuxRuntime.isBootstrapInstalled() && hasSharedStoragePermission()) {
            EmbeddedTermuxRuntime.ensureSharedStorageLink()
        }
        viewModel.refreshEnvironment()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setHostActivityVisible(true)
    }

    override fun onStop() {
        viewModel.setHostActivityVisible(false)
        super.onStop()
    }

    private fun prepareEmbeddedRuntime() {
        if (bootstrapDialogRequested && !EmbeddedTermuxRuntime.isBootstrapInstalled()) return
        bootstrapDialogRequested = true
        EmbeddedTermuxRuntime.setupBootstrapIfNeeded(this) {
            bootstrapDialogRequested = false
            requestStorageAccess()
        }
    }

    private fun requestStorageAccess() {
        // This app is a legacy-storage app (targetSdk 28 + requestLegacyExternalStorage),
        // so it uses the classic WRITE_EXTERNAL_STORAGE runtime permission, NOT the
        // "all files access" (MANAGE_EXTERNAL_STORAGE) model. On Android 11+ the OS
        // does not expose the all-files-access screen to a legacy app, so launching
        // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION there does nothing at all —
        // which is exactly why the button appeared dead. The runtime dialog below IS
        // the "許可する / 許可しない" screen the user sees, and granting it gives raw
        // read/write to /storage/emulated/0, which is what the ~/storage/shared link
        // and the Debian /mnt/android bind-mount need.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Already granted; (re)establish the shared-storage link. If it still
            // can't be created, or the user previously denied with "don't ask again",
            // send them to the app details screen to grant it manually.
            if (!finalizeSharedStorageSetup()) {
                launchLegacyStorageSettings()
            }
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
            hasRequestedStorageBefore
        ) {
            // The system will no longer show the runtime dialog (permanently denied),
            // so open the app details screen where the user can grant it by hand.
            launchLegacyStorageSettings()
        } else {
            hasRequestedStorageBefore = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // Open this app's details settings so the user can grant the storage permission by
    // hand. Used only when the runtime dialog won't appear (permanently denied) or the
    // link can't be built with a granted permission. NOT the all-files-access screen,
    // which the OS never exposes for this legacy app.
    private fun launchLegacyStorageSettings() {
        try {
            storageSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "設定 > アプリ > LDFA > 権限 > ストレージ から許可してください。",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Returns true when the shared-storage link is in place. false means Android
    // still cannot expose external storage (e.g. a stale grant after an over-install).
    private fun finalizeSharedStorageSetup(): Boolean {
        val linked = EmbeddedTermuxRuntime.ensureSharedStorageLink()
        viewModel.refreshEnvironment()
        return linked
    }

    // This legacy-storage app (targetSdk 28) uses WRITE_EXTERNAL_STORAGE, never the
    // all-files-access model. Environment.isExternalStorageManager() is false forever
    // for it, so checking that would keep onResume() from ever re-linking storage.
    private fun hasSharedStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    private fun startContainerWithNotificationPermission(container: ContainerInfo) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartContainer = container
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startContainer(container)
        }
    }
}
