package com.hatake716.linuxdesktop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
            viewModel.refreshEnvironment()
        }
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
