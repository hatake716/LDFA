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

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingStartContainer: ContainerInfo? = null
    private var pendingInstallationName: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingStartContainer?.let(viewModel::startContainer)
        pendingStartContainer = null
        pendingInstallationName?.let(viewModel::createContainer)
        pendingInstallationName = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstallationName = savedInstanceState?.getString("pendingInstallationName")
        enableEdgeToEdge()

        setContent {
            LinuxDesktopTheme {
                LinuxDesktopRoot(
                    viewModel = viewModel,
                    onInstall = ::installWithNotificationPermission,
                    onStartContainer = ::startContainerWithNotificationPermission,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingInstallationName", pendingInstallationName)
        super.onSaveInstanceState(outState)
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

    private fun installWithNotificationPermission(name: String) {
        if (needsNotificationPermission()) {
            pendingInstallationName = name
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.createContainer(name)
    }

    private fun needsNotificationPermission() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

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
