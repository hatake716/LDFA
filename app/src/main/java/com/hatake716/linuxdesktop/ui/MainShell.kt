package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.hatake716.linuxdesktop.data.*

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
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(
                    selected = state.selectedTab == MainTab.DESKTOPS,
                    onClick = { onTabSelected(MainTab.DESKTOPS) },
                    icon = { Icon(Icons.Rounded.Home, null) },
                    label = { Text("デスクトップ") },
                )
                NavigationBarItem(
                    selected = state.selectedTab == MainTab.SETTINGS,
                    onClick = { onTabSelected(MainTab.SETTINGS) },
                    icon = { Icon(Icons.Rounded.Settings, null) },
                    label = { Text("設定") },
                )
            }
        },
        floatingActionButton = {
            if (state.selectedTab == MainTab.DESKTOPS) {
                FloatingActionButton(onClick = onAdd, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = "追加")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (state.selectedTab) {
            MainTab.DESKTOPS -> DesktopsScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onRefresh = onRefresh,
                onStart = onStart,
                onStop = onStop,
                onRepair = onRepair,
                onLogs = onLogs,
                onDelete = onDelete,
            )
            MainTab.SETTINGS -> SettingsScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onOpenTerminal = onOpenTerminal,
                onOpenDisplay = onOpenDisplay,
                onBatterySettings = onBatterySettings,
                onAppSettings = onAppSettings,
                onRepair = onRepair,
            )
        }
    }
}
