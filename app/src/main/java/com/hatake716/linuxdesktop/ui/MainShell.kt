package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hatake716.linuxdesktop.data.ContainerInfo

private enum class ShellTab { DESKTOPS, TOOLS, SETTINGS }

@Composable
internal fun MainShell(
    state: MainUiState,
    onTabSelected: (MainTab) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onStart: (ContainerInfo) -> Unit,
    onStop: (ContainerInfo) -> Unit,
    onRepair: () -> Unit,
    onLogs: (ContainerInfo) -> Unit,
    onDelete: (ContainerInfo) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDisplay: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
) {
    var selectedTab by rememberSaveable {
        mutableStateOf(
            if (state.selectedTab == MainTab.SETTINGS) ShellTab.SETTINGS
            else ShellTab.DESKTOPS,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == ShellTab.DESKTOPS,
                    onClick = {
                        selectedTab = ShellTab.DESKTOPS
                        onTabSelected(MainTab.DESKTOPS)
                    },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("ホーム") },
                )
                NavigationBarItem(
                    selected = selectedTab == ShellTab.TOOLS,
                    onClick = { selectedTab = ShellTab.TOOLS },
                    icon = { Icon(Icons.Rounded.Build, contentDescription = null) },
                    label = { Text("ツール") },
                )
                NavigationBarItem(
                    selected = selectedTab == ShellTab.SETTINGS,
                    onClick = {
                        selectedTab = ShellTab.SETTINGS
                        onTabSelected(MainTab.SETTINGS)
                    },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("設定") },
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == ShellTab.DESKTOPS) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Linuxを追加") },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (selectedTab) {
            ShellTab.DESKTOPS -> DesktopsScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onAdd = onAdd,
                onRefresh = onRefresh,
                onStart = onStart,
                onStop = onStop,
                onRepair = onRepair,
                onLogs = onLogs,
                onDelete = onDelete,
            )

            ShellTab.TOOLS -> ToolsScreen(
                state = state,
                contentPadding = padding,
                onOpenTerminal = onOpenTerminal,
                onOpenDisplay = onOpenDisplay,
                onRepair = onRepair,
            )

            ShellTab.SETTINGS -> SettingsScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onOpenTerminal = onOpenTerminal,
                onBatterySettings = onBatterySettings,
                onAppSettings = onAppSettings,
                onRepair = onRepair,
            )
        }
    }
}
