package com.hatake716.linuxdesktop

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) finalizeSharedStorageSetup() else viewModel.refreshEnvironment()
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
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
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                launchAllFilesAccessSettings()
            }

            Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED -> {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            // Android reports the permission as granted. Normally this just links the
            // shared directory and completes. But after an over-install the special
            // "all files access" appop can be left granted-but-stale: the link cannot
            // be created and pressing the button would otherwise do nothing at all.
            // If the link still cannot be established, re-open the settings screen so
            // the user can toggle the permission off and on to refresh it.
            else -> {
                if (!finalizeSharedStorageSetup() &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ) {
                    launchAllFilesAccessSettings()
                }
            }
        }
    }

    // Open the system "all files access" settings for this app. On some devices the
    // package-scoped intent has no handler; fall back to the app-list variant, and if
    // neither resolves, tell the user instead of silently doing nothing.
    private fun launchAllFilesAccessSettings() {
        val scoped = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            allFilesAccessLauncher.launch(scoped)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the non-scoped list screen below.
        }
        try {
            allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "設定 > アプリ > LDFA > 権限 から「すべてのファイルへのアクセス」を許可してください。",
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

    private fun hasSharedStoragePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

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
