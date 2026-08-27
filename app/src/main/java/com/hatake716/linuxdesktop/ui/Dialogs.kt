package com.hatake716.linuxdesktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.hatake716.linuxdesktop.data.*

@Composable
internal fun CreateContainerDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("Debian XFCE") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Rounded.Computer, contentDescription = null) },
        title = { Text("新しいDebian XFCE") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Debian + XFCE", fontWeight = FontWeight.SemiBold)
                            Text(
                                "日本語表示、Fcitx5/Mozc、Google Chrome、sudoを自動設定します。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("環境の名前") },
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "3〜5GB以上の空き容量を推奨します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !busy,
                shape = RoundedCornerShape(12.dp),
            ) { Text("インストール") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("キャンセル") }
        },
        shape = RoundedCornerShape(26.dp),
    )
}

@Composable
internal fun DeleteContainerDialog(
    container: ContainerInfo,
    onDismiss: () -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("${container.name}を削除") },
        text = {
            Column {
                Text("Debian環境内のアプリと設定は完全に削除され、元に戻せません。")
            }
        },
        confirmButton = {
            Button(
                // The Android-shared-storage feature is retired; there is no
                // per-environment shared folder to purge any more.
                onClick = { onDelete(false) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("削除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
        shape = RoundedCornerShape(26.dp),
    )
}

@Composable
internal fun LogsDialog(title: String, logs: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    SelectionContainer {
                        Text(
                            logs,
                            modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
            }
        }
    }
}

@Composable
internal fun OperationOverlay(desktopStarting: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 10.dp) {
            Row(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (desktopStarting) "デスクトップを起動しています" else "処理しています",
                        fontWeight = FontWeight.Medium,
                    )
                    if (desktopStarting) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "デスクトップが表示されるまで少し時間がかかります。初回や更新直後は数分かかる場合があります。そのままお待ちください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
