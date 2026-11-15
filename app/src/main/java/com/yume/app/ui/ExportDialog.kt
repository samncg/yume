package com.yume.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yume.app.data.ExportFormat
import com.yume.app.data.Exporter

/** Export in any format, to the share sheet or to a file the user picks. */
@Composable
fun ExportDialog(
    controller: EditorController,
    width: Int,
    height: Int,
    onDismiss: () -> Unit
) {
    val busy by controller.exporting.collectAsStateWithLifecycle()
    val savers = ExportFormat.entries.map { f ->
        f to rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri ->
            if (uri != null) controller.exportTo(f, uri)
        }
    }
    val big = width.toLong() * height > Exporter.MAX_RASTER_PIXELS
    Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(400.dp)) {
            PanelHeader("Export", onClose = onDismiss)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Save PNG to Gallery", enabled = !busy) {
                    controller.export()
                }
            }
            DividerLine()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((format, saver) in savers) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(format.label, style = MaterialTheme.typography.bodyMedium, color = YumeColors.text)
                            val note = when (format) {
                                ExportFormat.Png -> "Full size, lossless"
                                ExportFormat.Jpeg, ExportFormat.Webp ->
                                    if (big) "Scaled to 16 MP (use PNG for full size)" else "Full size"
                                ExportFormat.Pdf -> "One page, full size"
                                ExportFormat.Ora -> "Every layer, for other paint apps"
                                ExportFormat.Timelapse -> "Replay of your painting"
                            }
                            Text(note, style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
                        }
                        SmallButton("Share", enabled = !busy) { controller.exportShare(format) }
                        Spacer(Modifier.width(6.dp))
                        SmallButton("Save…", enabled = !busy) { saver.launch(controller.suggestedName(format)) }
                    }
                }
            }
            if (busy) {
                // The exporter's progress, polled while it runs.
                var p by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) {
                        p = Exporter.progress
                        kotlinx.coroutines.delay(100)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (p >= 0f) "Exporting… ${(p * 100).toInt().coerceIn(0, 100)}%" else "Exporting…",
                    style = MaterialTheme.typography.bodySmall, color = YumeColors.accent
                )
                Spacer(Modifier.height(6.dp))
                if (p >= 0f) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        color = YumeColors.accent,
                        trackColor = YumeColors.track,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(
                        color = YumeColors.accent,
                        trackColor = YumeColors.track,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
