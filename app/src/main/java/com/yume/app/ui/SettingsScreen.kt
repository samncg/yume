package com.yume.app.ui

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yume.app.data.AppSettings
import kotlin.math.pow
import kotlin.math.roundToInt

/** Whether the Settings page is showing (opened from the gallery or Actions). */
object SettingsHost {
    var open by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * Settings: appearance (background, accent, interface size), drawing (motion
 * filtering, prediction, pressure curve, hold timings) and gestures. Every
 * change applies immediately and is remembered.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(interactionSource = null, indication = null) { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 24.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(YumeColors.bar)
                    .clickable(interactionSource = null, indication = null) { }
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                PanelHeader("Settings", onClose)
                Spacer(Modifier.height(4.dp))

                SectionLabel("Appearance")
                Spacer(Modifier.height(8.dp))
                Text("Background", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    YumeThemes.themes.forEach { t ->
                        val selected = AppSettings.theme == t.id
                        Column(
                            Modifier
                                .width(78.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) YumeColors.accentSoft else YumeColors.panelAlt)
                                .clickable { AppSettings.setThemeId(t.id) }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                Modifier
                                    .size(56.dp, 34.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(t.bg)
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(7.dp))
                                    .padding(5.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(Modifier.size(14.dp, 24.dp).clip(RoundedCornerShape(3.dp)).background(t.panelAlt))
                                Box(Modifier.size(28.dp, 10.dp).clip(RoundedCornerShape(3.dp)).background(t.panel))
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                t.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) YumeColors.accent else YumeColors.textDim
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Accent", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    YumeThemes.accents.forEach { a ->
                        val selected = AppSettings.accent == a.id
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(a.color)
                                .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { AppSettings.setAccentId(a.id) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                YumeSlider(
                    value = AppSettings.uiScale,
                    onChange = { AppSettings.setUiScaleValue((it * 20).roundToInt() / 20f) },
                    label = "Interface size",
                    valueLabel = "${(AppSettings.uiScale * 100).roundToInt()}%",
                    min = 0.85f,
                    max = 1.15f
                )

                DividerLine()
                SectionLabel("Drawing")
                Spacer(Modifier.height(4.dp))
                YumeSlider(
                    value = AppSettings.motionFiltering,
                    onChange = { AppSettings.setMotionFilteringValue(it) },
                    label = "Motion filtering",
                    valueLabel = if (AppSettings.motionFiltering < 0.01f) "Off" else formatPercent(AppSettings.motionFiltering)
                )
                Hint("Removes hand tremor from slow, careful lines; fast strokes stay sharp and on time.")
                ToggleRow(Glyph.Brush, "Predict the stroke tip", AppSettings.prediction) {
                    AppSettings.setPredictionValue(it)
                }
                Hint("Draws the line ahead to where the pen is going, so it keeps up with the pen tip.")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        YumeSlider(
                            value = AppSettings.pressureCurve,
                            onChange = { AppSettings.setPressureCurveValue((it * 20).roundToInt() / 20f) },
                            label = "Pressure curve",
                            valueLabel = when {
                                AppSettings.pressureCurve < 0.95f -> "Light touch"
                                AppSettings.pressureCurve > 1.05f -> "Firm touch"
                                else -> "Linear"
                            },
                            min = 0.4f,
                            max = 2.5f
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PressureGraph(AppSettings.pressureCurve)
                }
                YumeSlider(
                    value = AppSettings.quickShapeHoldMs.toFloat(),
                    onChange = { AppSettings.setQuickShapeHold(((it / 50f).roundToInt() * 50)) },
                    label = "QuickShape hold",
                    valueLabel = "${AppSettings.quickShapeHoldMs} ms",
                    min = 250f,
                    max = 1200f
                )
                YumeSlider(
                    value = AppSettings.eyedropperHoldMs.toFloat(),
                    onChange = { AppSettings.setEyedropperHold(((it / 50f).roundToInt() * 50)) },
                    label = "Eyedropper hold",
                    valueLabel = "${AppSettings.eyedropperHoldMs} ms",
                    min = 300f,
                    max = 1500f
                )

                DividerLine()
                SectionLabel("Gestures")
                Spacer(Modifier.height(4.dp))
                ToggleRow(Glyph.Undo, "Two-finger tap: undo", AppSettings.twoFingerUndo) { AppSettings.updateTwoFingerUndo(it) }
                ToggleRow(Glyph.Redo, "Three-finger tap: redo", AppSettings.threeFingerRedo) { AppSettings.updateThreeFingerRedo(it) }
                ToggleRow(Glyph.History, "Three-finger swipe: scrub history", AppSettings.threeFingerScrub) {
                    AppSettings.updateThreeFingerScrub(it)
                }
                Hint("Drag three fingers left to undo step by step, back right to redo.")
                ToggleRow(Glyph.Eye, "Four-finger tap: hide interface", AppSettings.fourFingerUi) { AppSettings.updateFourFingerUi(it) }
                ToggleRow(Glyph.Undo, "Hold undo / redo to repeat", AppSettings.holdToRepeatUndo) { AppSettings.updateHoldToRepeatUndo(it) }
                Hint("Drag the colour chip onto the canvas to fill (ColorDrop); hold before letting go and slide sideways to set the threshold.")
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = YumeColors.textDim,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    )
}

/** The pressure response: output against pen pressure. */
@Composable
private fun PressureGraph(curve: Float) {
    val accent = YumeColors.accent
    val track = YumeColors.track
    Canvas(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(YumeColors.panelAlt)
            .padding(6.dp)
    ) {
        val w = size.width
        val h = size.height
        drawLine(track, Offset(0f, h), Offset(w, 0f), strokeWidth = 1.5f)
        val p = Path()
        for (i in 0..24) {
            val x = i / 24f
            val y = x.toDouble().pow(curve.toDouble()).toFloat()
            if (i == 0) p.moveTo(0f, h) else p.lineTo(x * w, h - y * h)
        }
        drawPath(p, accent, style = Stroke(width = 3f))
    }
}
