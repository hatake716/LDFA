package com.hatake716.linuxdesktop.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hatake716.linuxdesktop.data.DesktopStartupMonitor
import com.hatake716.linuxdesktop.data.DesktopStartupProgress
import com.hatake716.linuxdesktop.ui.theme.LinuxDesktopTheme

@Composable
internal fun DesktopStartupOverlay(progress: DesktopStartupProgress, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    var follow by rememberSaveable(progress.containerId) { mutableStateOf(true) }
    LaunchedEffect(progress.logs, follow) {
        if (follow) {
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
    }
    BackHandler { if (!progress.busy) onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.safeDrawingPadding().padding(12.dp)
                .widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.9f),
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 10.dp,
        ) {
            BoxWithConstraints {
                val compact = maxHeight < 400.dp
                Column(Modifier.padding(if (compact) 12.dp else 18.dp)) {
                    Text(
                        if (progress.busy) "デスクトップを起動しています" else "デスクトップを起動できませんでした",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!compact) Text(progress.containerName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (progress.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(progress.phase, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text("起動ログ", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        Modifier.weight(1f).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                progress.logs.ifBlank { "ログの出力を待っています…" },
                                modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自動スクロール", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = follow, onCheckedChange = { follow = it })
                        if (!progress.busy) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onDismiss) { Text("閉じる") }
                        }
                    }
                    if (progress.busy && !compact) Text(
                        "起動が完了するとLinux画面へ切り替わります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Same-window overlay keeps the X11 Surface attached while startup probes draw underneath. */
internal fun attachDesktopStartupOverlay(activity: Activity, monitor: DesktopStartupMonitor) {
    val root = activity.findViewById<ViewGroup>(android.R.id.content)
    val tag = "ldfa-desktop-startup-overlay"
    if (root.findViewWithTag<View>(tag) != null) return
    val overlay = ComposeView(activity).apply {
        this.tag = tag
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
    activity.addContentView(overlay, ViewGroup.LayoutParams(-1, -1))
    overlay.setContent {
        val progress by monitor.progress.collectAsStateWithLifecycle()
        SideEffect { overlay.visibility = if (progress.visible) View.VISIBLE else View.GONE }
        if (progress.visible) LinuxDesktopTheme {
            DesktopStartupOverlay(progress, monitor::dismissFailure)
        }
    }
}
