package com.yume.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yume.app.data.AppSettings
import com.yume.app.data.Customization
import com.yume.app.engine.BrushCategory
import com.yume.app.engine.BrushLibrary
import com.yume.app.engine.BrushPreset
import com.yume.app.engine.Tool
import kotlin.math.roundToInt

/** Whether the Settings page is showing (opened from the gallery or Actions). */
object SettingsHost {
    var open by mutableStateOf(false)
}

private val SETTINGS_TABS = listOf("Appearance", "Drawing", "Gestures", "Shortcuts", "Layout", "Defaults")

/**
 * Settings, in tabs: appearance, drawing, what every gesture does, keyboard
 * shortcuts, the toolbar's layout, and the defaults a project starts with.
 * Every change applies immediately and is remembered.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = {
            Customization.learning = null
            onClose()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(interactionSource = null, indication = null) {
                    Customization.learning = null
                    onClose()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(YumeColors.bar)
                    .clickable(interactionSource = null, indication = null) { }
                    // A dialog is its own window: keys for a new shortcut are
                    // caught here while one is being taught.
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        Customization.learning != null && Customization.handleKey(e.nativeKeyEvent)
                    }
                    .padding(20.dp)
            ) {
                PanelHeader("Settings") {
                    Customization.learning = null
                    onClose()
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SETTINGS_TABS.forEachIndexed { i, name ->
                        Chip(name, selected = tab == i) {
                            Customization.learning = null
                            tab = i
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (tab) {
                        0 -> AppearanceTab()
                        1 -> DrawingTab()
                        2 -> GesturesTab()
                        3 -> ShortcutsTab()
                        4 -> LayoutTab()
                        else -> DefaultsTab()
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    LaunchedEffect(Customization.learning) {
        if (Customization.learning != null) runCatching { focus.requestFocus() }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) YumeColors.accent else YumeColors.text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) YumeColors.accentSoft else YumeColors.panelAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun SmallChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) YumeColors.accent else YumeColors.text,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) YumeColors.accentSoft else YumeColors.panelAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
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

/** A setting with one value out of a list: tap to open the choices. */
@Composable
private fun PickerRow(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(YumeColors.panelAlt)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = YumeColors.text, modifier = Modifier.weight(1f))
            Text(
                options.firstOrNull { it.first == value }?.second ?: value,
                style = MaterialTheme.typography.bodyMedium,
                color = YumeColors.accent
            )
            Spacer(Modifier.width(8.dp))
            GlyphIcon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, YumeColors.textDim, size = 14.dp)
        }
        if (open) {
            FlowRow(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { (id, name) ->
                    SmallChip(name, selected = id == value) {
                        onPick(id)
                        open = false
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Appearance

@Composable
private fun AppearanceTab() {
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
}

// ------------------------------------------------------------------- Drawing

@Composable
private fun DrawingTab() {
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
    Hint(
        "Pressure curves belong to each brush now: open Brush Studio, More settings, " +
            "Pressure curve, and drag the curve for that brush."
    )
}

// ------------------------------------------------------------------ Gestures

@Composable
private fun GesturesTab() {
    SectionLabel("Touch and pen")
    Spacer(Modifier.height(6.dp))
    val all = Customization.Action.entries
    Customization.Gesture.entries.forEach { g ->
        val choices = g.choices ?: all
        PickerRow(
            label = g.label,
            value = Customization.actionFor(g).name,
            options = choices.map { it.name to (if (g == Customization.Gesture.PenButton && it == Customization.Action.Eraser) "Erase while held" else it.label) },
            onPick = { name -> all.firstOrNull { it.name == name }?.let { Customization.setGesture(g, it) } }
        )
    }
    Hint(
        "Two fingers always pan, zoom and rotate the canvas. Three-finger slides are left to the " +
            "system (screenshot and minimise on HyperOS)."
    )
    ToggleRow(Glyph.Undo, "Hold or slide undo / redo", AppSettings.holdToRepeatUndo) { AppSettings.updateHoldToRepeatUndo(it) }
    Hint("Hold the undo or redo button to keep stepping; slide up and down along it to scrub through history.")
    DividerLine()
    RingSettings()
    DividerLine()
    PenGestureSettings()
}

/** What the shortcut ring holds, in order clockwise from the top. */
@Composable
private fun RingSettings() {
    SectionLabel("Shortcut ring")
    Hint("Clockwise from the top. Drag the handle (or long-press a row) to reorder. With the ring open under two fingers, orbit one finger around the other to point at an item, then lift to choose it.")
    val ring = Customization.ringItems
    ReorderColumn(
        items = ring,
        key = { it.name },
        onMove = { from, to -> Customization.moveRingItem(from, to) }
    ) { a, handle ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(YumeColors.panelAlt)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(handle.size(36.dp), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Grip, YumeColors.textDim, size = 16.dp)
            }
            Spacer(Modifier.width(6.dp))
            val (glyph, text) = ringFace(a)
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when {
                    glyph != null -> GlyphIcon(glyph, YumeColors.text, size = 18.dp)
                    text != null -> Text(text, style = MaterialTheme.typography.labelSmall, color = YumeColors.text, maxLines = 1)
                    else -> Box(Modifier.size(16.dp).clip(CircleShape).background(YumeColors.accent))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(a.label, style = MaterialTheme.typography.bodyMedium, color = YumeColors.text, modifier = Modifier.weight(1f))
            if (ring.size > Customization.RING_MIN) {
                SmallButton("\u2212") { Customization.removeRingItem(a) }
            }
        }
    }
    val more = Customization.RING_CHOICES.filter { it !in ring }
    if (ring.size < Customization.RING_MAX && more.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Add to the ring", style = MaterialTheme.typography.labelLarge, color = YumeColors.textDim)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            more.forEach { a -> SmallChip("+ " + a.label, selected = false) { Customization.addRingItem(a) } }
        }
    } else if (ring.size >= Customization.RING_MAX) {
        Hint("The ring holds up to ${Customization.RING_MAX} items.")
    }
    Spacer(Modifier.height(8.dp))
    SmallButton("Reset ring") { Customization.resetRing() }
}

// ----------------------------------------------------------------- Shortcuts

@Composable
private fun ShortcutsTab() {
    Hint("For a keyboard connected to the tablet. Tap Set, then press the keys. Esc cancels.")
    Customization.Action.entries.filter { it != Customization.Action.None }.forEach { a ->
        val combo = Customization.shortcuts[a]
        val teaching = Customization.learning == a
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(YumeColors.panelAlt)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(a.label, style = MaterialTheme.typography.bodyMedium, color = YumeColors.text, modifier = Modifier.weight(1f))
            Text(
                text = when {
                    teaching -> "Press keys…"
                    combo != null -> combo
                    else -> "—"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (teaching) YumeColors.accent else YumeColors.textDim
            )
            Spacer(Modifier.width(10.dp))
            SmallButton(if (teaching) "Cancel" else "Set") {
                Customization.learning = if (teaching) null else a
            }
            if (combo != null && !teaching) {
                Spacer(Modifier.width(6.dp))
                SmallButton("Clear") { Customization.clearShortcut(a) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    SmallButton("Reset all shortcuts") { Customization.resetShortcuts() }
}

// -------------------------------------------------------------------- Layout

@Composable
private fun LayoutTab() {
    SectionLabel("Top bar")
    Hint("The pills along the top of the editor. Drag the handle (or long-press a row) to change their order, and choose where each one sits, or hide it. A hidden Document pill leaves the gallery a back gesture away; hidden panels stay reachable through gestures and shortcuts. History & actions always stays, so Settings is never out of reach.")
    ReorderColumn(
        items = Customization.pillOrder,
        key = { it.name },
        onMove = { from, to -> Customization.movePill(from, to) }
    ) { pill, handle ->
        val slot = Customization.pillSlots[pill] ?: pill.defaultSlot
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(YumeColors.panelAlt)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(handle.size(32.dp), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Grip, YumeColors.textDim, size = 16.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pill.label, style = MaterialTheme.typography.bodyMedium,
                        color = if (slot == Customization.PillSlot.Hidden) YumeColors.textDim else YumeColors.text
                    )
                    Text(pill.detail, style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
                }
            }
            Spacer(Modifier.height(6.dp))
            val slots = Customization.PillSlot.entries.filter { pill.hidable || it != Customization.PillSlot.Hidden }
            SegmentedRow(
                options = slots.map { it.label },
                selectedIndex = slots.indexOf(slot),
                onSelect = { Customization.setPillSlot(pill, slots[it]) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    SmallButton("Reset top bar") { Customization.resetPills() }
    DividerLine()

    SectionLabel("Toolbar")
    Hint("Drag the handle (or long-press a row) to reorder the tools. Remove a tool with −, add one back from More tools. Removed tools stay reachable through gestures and shortcuts.")
    val shown = Customization.visibleTools
    ReorderColumn(
        items = shown,
        key = { it.name },
        onMove = { from, to -> Customization.moveVisibleTool(from, to) }
    ) { t, handle ->
        val (glyph, label, _) = toolChrome(t)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(YumeColors.panelAlt)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(handle.size(36.dp), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Grip, YumeColors.textDim, size = 16.dp)
            }
            Spacer(Modifier.width(6.dp))
            GlyphIcon(glyph, YumeColors.text, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = YumeColors.text, modifier = Modifier.weight(1f))
            if (t != com.yume.app.engine.Tool.Brush) {
                SmallButton("−") { Customization.setToolHidden(t, true) }
            }
        }
    }
    val more = Customization.toolOrder.filter { it in Customization.hiddenTools }
    if (more.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("More tools", style = MaterialTheme.typography.labelLarge, color = YumeColors.textDim)
        Spacer(Modifier.height(4.dp))
        more.forEach { t ->
            val (glyph, label, _) = toolChrome(t)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(YumeColors.panelAlt.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphIcon(glyph, YumeColors.textDim, size = 18.dp)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = YumeColors.textDim, modifier = Modifier.weight(1f))
                SmallButton("+ Add") { Customization.setToolHidden(t, false) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    SmallButton("Reset toolbar") { Customization.resetLayout() }
    DividerLine()
    SectionLabel("Editor")
    Spacer(Modifier.height(4.dp))
    ToggleRow(Glyph.Sliders, "Size and opacity rail on the right", AppSettings.railOnRight) {
        AppSettings.setRailOnRightValue(it)
    }
    YumeSlider(
        value = AppSettings.uiScale,
        onChange = { AppSettings.setUiScaleValue((it * 20).roundToInt() / 20f) },
        label = "Interface size",
        valueLabel = "${(AppSettings.uiScale * 100).roundToInt()}%",
        min = 0.85f,
        max = 1.15f
    )
}

// ------------------------------------------------------------------ Defaults

private val DEFAULT_COLORS = listOf(
    0xFF000000, 0xFF2B2B33, 0xFF6B6B70, 0xFFFFFFFF, 0xFFC0392B, 0xFFE67E22,
    0xFFF1C40F, 0xFF27AE60, 0xFF16A085, 0xFF2980B9, 0xFF8E44AD, 0xFFD35490
).map { it.toInt() }

@Composable
private fun DefaultsTab() {
    val context = LocalContext.current
    val presets: List<BrushPreset> = remember {
        runCatching { (context.applicationContext as com.yume.app.YumeApp).presetStore.load() }
            .getOrDefault(BrushLibrary.defaults)
    }
    ToggleRow(Glyph.Pin, "Keep tool settings between projects", Customization.persistToolSettings) {
        Customization.setPersistToolSettingsValue(it)
    }
    Hint(
        if (Customization.persistToolSettings)
            "A project opens with the tool, brush, colour and tool options you last used anywhere."
        else "Every project opens with the defaults below."
    )
    DividerLine()
    SectionLabel("A project starts with")
    Spacer(Modifier.height(6.dp))
    PickerRow(
        "Tool", Customization.defaultTool.name,
        Tool.entries.filter { it != Tool.Transform }.map { it.name to toolChrome(it).second }
    ) { name -> Tool.entries.firstOrNull { it.name == name }?.let { Customization.setDefaultToolValue(it) } }
    fun options(cat: BrushCategory, withFirst: Boolean) =
        (if (withFirst) listOf("" to "First in the library") else emptyList()) +
            presets.filter { it.category == cat }.map { it.id to it.name }
    PickerRow("Brush", Customization.defaultBrush, options(BrushCategory.Brush, false)) {
        Customization.setDefaultBrushValue(it)
    }
    PickerRow("Eraser", Customization.defaultEraser, options(BrushCategory.Eraser, true)) {
        Customization.setDefaultEraserValue(it)
    }
    PickerRow("Blender", Customization.defaultSmudge, options(BrushCategory.Smudge, true)) {
        Customization.setDefaultSmudgeValue(it)
    }
    Spacer(Modifier.height(8.dp))
    Text("Colour", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DEFAULT_COLORS.forEach { c ->
            val selected = Customization.defaultColor == c
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (selected) 3.dp else 1.dp, if (selected) YumeColors.accent else Color(0x44FFFFFF), CircleShape)
                    .clickable { Customization.setDefaultColorValue(c) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    YumeSlider(
        value = Customization.defaultAssist,
        onChange = { Customization.setDefaultAssistValue(it) },
        label = "Stroke assist",
        valueLabel = formatPercent(Customization.defaultAssist)
    )
    YumeSlider(
        value = Customization.defaultFillTolerance.toFloat(),
        onChange = { Customization.setDefaultFillToleranceValue(it.roundToInt()) },
        label = "Fill tolerance",
        valueLabel = "${Customization.defaultFillTolerance}",
        min = 0f,
        max = 255f
    )
    ToggleRow(Glyph.Shape, "QuickShape", Customization.defaultQuickShape) {
        Customization.setDefaultQuickShapeValue(it)
    }
    ToolDefaults()
}

@Composable
private fun <E : Enum<E>> DefaultChoice(label: String, key: String, entries: List<E>, def: E, name: (E) -> String) {
    val cur = entries.firstOrNull { it.name == Customization.tdString(key, def.name) } ?: def
    Text(label, style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(entries.map(name), entries.indexOf(cur), { Customization.setTd(key, entries[it].name) })
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DefaultToggle(glyph: Glyph, label: String, key: String, def: Boolean) {
    ToggleRow(glyph, label, Customization.tdBool(key, def)) { Customization.setTd(key, it) }
}

/** Each tool's starting options. */
@Composable
private fun ToolDefaults() {
    val C = Customization
    DividerLine()
    SectionLabel("Selection")
    Spacer(Modifier.height(6.dp))
    DefaultChoice("Shape", "selectionShape", com.yume.app.engine.SelectionShape.entries, com.yume.app.engine.SelectionShape.Lasso) { it.label }
    YumeSlider(
        value = C.tdInt("wandTolerance", 30).toFloat(),
        onChange = { C.setTd("wandTolerance", it.roundToInt()) },
        label = "Auto-select tolerance",
        valueLabel = "${C.tdInt("wandTolerance", 30)}",
        min = 1f, max = 100f
    )
    DividerLine()
    SectionLabel("Paint bucket")
    Spacer(Modifier.height(6.dp))
    DefaultToggle(Glyph.Layers, "Fill using the current layer only", "fillLayerOnly", false)
    DefaultToggle(Glyph.Select, "Tap inside a selection fills all of it", "fillSelection", true)
    YumeSlider(
        value = C.tdInt("fillExpand", 1).toFloat(),
        onChange = { C.setTd("fillExpand", it.roundToInt()) },
        label = "Grow under edges",
        valueLabel = "${C.tdInt("fillExpand", 1)} px",
        min = 0f, max = 8f
    )
    DividerLine()
    SectionLabel("Gradient")
    Spacer(Modifier.height(6.dp))
    DefaultChoice("Kind", "gradientKind", com.yume.app.engine.Gradients.Kind.entries, com.yume.app.engine.Gradients.Kind.Linear) { it.label }
    DividerLine()
    SectionLabel("Shape")
    Spacer(Modifier.height(6.dp))
    DefaultChoice("Shape", "shapeKind", com.yume.app.engine.ShapeKind.entries, com.yume.app.engine.ShapeKind.Ellipse) { it.label }
    DefaultToggle(Glyph.Shape, "Perfect (square, circle)", "shapePerfect", false)
    YumeSlider(
        value = C.tdInt("shapeSides", 5).toFloat(),
        onChange = { C.setTd("shapeSides", it.roundToInt()) },
        label = "Polygon / star sides",
        valueLabel = "${C.tdInt("shapeSides", 5)}",
        min = 3f, max = 12f
    )
    DividerLine()
    SectionLabel("Liquify")
    Spacer(Modifier.height(6.dp))
    DefaultChoice("Mode", "liquifyMode", com.yume.app.engine.LiquifyMode.entries, com.yume.app.engine.LiquifyMode.Push) { it.label }
    YumeSlider(
        value = C.tdFloat("liquifySize", 160f),
        onChange = { C.setTd("liquifySize", it.roundToInt().toFloat()) },
        label = "Size",
        valueLabel = "${C.tdFloat("liquifySize", 160f).roundToInt()} px",
        min = 10f, max = 600f
    )
    YumeSlider(
        value = C.tdFloat("liquifyStrength", 0.5f),
        onChange = { C.setTd("liquifyStrength", it) },
        label = "Strength",
        valueLabel = formatPercent(C.tdFloat("liquifyStrength", 0.5f))
    )
    DividerLine()
    SectionLabel("Text")
    Spacer(Modifier.height(6.dp))
    Text("Font", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(TEXT_FONT_LABELS, C.tdInt("textFont", 0).coerceIn(0, TEXT_FONT_LABELS.size - 1), { C.setTd("textFont", it) })
    Spacer(Modifier.height(8.dp))
    YumeSlider(
        value = C.tdFloat("textSize", 96f),
        onChange = { C.setTd("textSize", it.roundToInt().toFloat()) },
        label = "Size",
        valueLabel = "${C.tdFloat("textSize", 96f).roundToInt()} px",
        min = 12f, max = 600f
    )
    DefaultToggle(Glyph.Text, "Bold", "textBold", false)
    DefaultToggle(Glyph.Text, "Italic", "textItalic", false)
    Text("Alignment", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("Left", "Centre", "Right"), C.tdInt("textAlign", 0).coerceIn(0, 2), { C.setTd("textAlign", it) })
    Spacer(Modifier.height(10.dp))
    SmallButton("Reset tool options") { C.resetToolDefaults() }
}
