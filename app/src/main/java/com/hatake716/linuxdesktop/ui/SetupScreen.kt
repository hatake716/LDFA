package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hatake716.linuxdesktop.R

@Composable
internal fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CircularProgressIndicator()
            Text("Linux環境を確認しています", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SetupScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onInstall: (String) -> Unit,
    onRestore: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("マイデスクトップ") }
    val progress = state.installation
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp)) {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), null, tint = androidx.compose.ui.graphics.Color.Unspecified)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("LDFA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Linux Desktop for Android", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !progress.busy) {
                    Icon(Icons.Rounded.Refresh, "状態を再確認")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("いつものスマホに、\nLinuxの作業場所を。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("DebianとXFCEデスクトップをアプリ内に用意します。ブラウザー、日本語入力、ターミナルをひとつの環境で使えます。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("はじめる前に", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SetupNote(Icons.Rounded.Storage, "空き容量 5GB以上", "追加するファイルやアプリの分も余裕を持たせてください。")
                    SetupNote(Icons.Rounded.Wifi, "Wi-Fiと充電をおすすめします", "初回は大きなダウンロードがあり、端末と回線により時間がかかります。")
                    SetupNote(Icons.Rounded.Security, "root化・別アプリは不要", "Linuxのデータはこのアプリ内に保存されます。")
                }
            }
            if (progress.busy) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(progress.message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("画面を回転したり、別のアプリに移動したりしても処理は続きます。Androidにより処理が終了した場合は、アプリを開いて再開できます。", style = MaterialTheme.typography.bodyMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("1 準備", fontWeight = if (progress.phase < 3) FontWeight.Bold else FontWeight.Normal)
                            Text("2 Linuxの構築")
                            Text("3 起動")
                        }
                    }
                }
            } else {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("デスクトップの名前") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                (progress.error ?: state.environmentError)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (state.environmentError != null) onRefresh() else onInstall(name.trim()) }, enabled = name.isNotBlank() || state.environmentError != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (state.environmentError != null) "状態を再確認" else if (progress.error == null) "Linuxをインストール" else "もう一度試す", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("バックアップから復元する")
                    }
                    Text("導入後、ホームの「デスクトップを開く」から起動できます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SetupNote(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
