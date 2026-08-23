package com.hatake716.linuxdesktop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                allFilesAccessLauncher.launch(intent)
            }

            Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED -> {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            else -> finalizeSharedStorageSetup()
        }
    }

    private fun finalizeSharedStorageSetup() {
        EmbeddedTermuxRuntime.ensureSharedStorageLink()
        viewModel.refreshEnvironment()
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
