package com.yume.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.animateFloat
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Whether the gestures and tips guide is showing (gallery, Actions). */
object GuideHost {
    var open by mutableStateOf(false)
}

private class GuideItem(val gesture: String, val action: String)
private class GuideSection(val title: String, val items: List<GuideItem>)

private val GUIDE = listOf(
    GuideSection(
        "Canvas gestures",
        listOf(
            GuideItem("Pen or one finger", "Paint, erase or smudge with the active tool (one finger only when Finger painting is on)"),
            GuideItem("Two fingers", "Pan, pinch to zoom, twist to rotate; the point between your fingers stays put"),
            GuideItem("Two-finger tap", "Undo"),
            GuideItem("Three-finger tap", "Redo"),
            GuideItem("Four-finger tap", "Hide or show the interface"),
            GuideItem("Two-finger hold", "Shortcut ring (the same menu as the Xiaomi pen's squeeze); touch an item to run it"),
            GuideItem("Two fingers while a tool is working", "Always a pan: the tool's touch is undone, nothing is drawn")
        )
    ),
    GuideSection(
        "While drawing",
        listOf(
            GuideItem("Hold still at the end of a stroke", "QuickShape: the stroke snaps to a line, arc, ellipse or polygon; keep holding and move to adjust it"),
            GuideItem("Hold still at the start of a stroke", "Eyedropper: pick the colour under the pen (brush, eraser, smudge)"),
            GuideItem("Drag the colour chip onto the canvas", "ColorDrop: fill the area under your finger with the current colour"),
            GuideItem("…rest before letting go, then slide sideways", "Set the ColorDrop threshold live; lift to keep it"),
            GuideItem("Pen barrel button", "Paint with the eraser while it is held"),
            GuideItem("Two-finger pan with any tool", "Never draws, fills or moves anything")
        )
    ),
    GuideSection(
        "Undo and history",
        listOf(
            GuideItem("Undo / redo buttons", "On the side rail; tap to step once"),
            GuideItem("Hold the button", "Keep stepping, faster the longer you hold"),
            GuideItem("Slide along the button", "Scrub through history: down undoes step by step, back up redoes"),
            GuideItem("History panel (clock)", "Jump to any step, or delete one step and replay the rest"),
            GuideItem("Working… card", "Shown during long operations; Cancel puts everything back")
        )
    ),
    GuideSection(
        "Layers",
        listOf(
            GuideItem("Long-press a layer, then drag", "Reorder; drop onto a group to put it inside"),
            GuideItem("Drag a grouped layer to the left", "Take it out of the group (it lands just below)"),
            GuideItem("Layer row buttons", "Visibility, alpha lock, clipping and mask (long-press the mask button to remove the mask)"),
            GuideItem("Opacity and blend mode", "At the top of the Layers panel, for the selected layer")
        )
    ),
    GuideSection(
        "Brushes",
        listOf(
            GuideItem("Brush Studio groups", "Sketching, Inking, Calligraphy, Painting, Airbrushing, Textures, Elements…"),
            GuideItem("Long-press a brush", "Add it to (or remove it from) ★ Favorites"),
            GuideItem("Recent", "The brushes you painted with last"),
            GuideItem("More settings → Import brushes…", "Procreate .brush / .brushset, Photoshop .abr, Clip Studio .sut / .sutg, GIMP .gbr, images; several files at once"),
            GuideItem("Side rail sliders", "Brush size and opacity; drag the rail by its handle to move it")
        )
    ),
    GuideSection(
        "Selection, transform and guides",
        listOf(
            GuideItem("Auto selection: tap", "Select by colour; raise Tolerance to take soft edges too"),
            GuideItem("Drag inside a selection", "Move the selection outline"),
            GuideItem("Transform: drag", "Move the layer or selection"),
            GuideItem("Transform: pinch or twist", "Scale or rotate; a two-finger pan still moves the view"),
            GuideItem("Guides panel open", "Drag vanishing points, ruler ends and the symmetry centre")
        )
    ),
    GuideSection(
        "Gallery and files",
        listOf(
            GuideItem("Tap a card", "Open the artwork"),
            GuideItem("Card menu (… or long-press)", "Pin to the top, rename, back up, duplicate or delete"),
            GuideItem("New canvas", "Pick a shape and a resolution up to 16K, or type a size"),
            GuideItem("Restore…", "Bring back a project from a backup .zip"),
            GuideItem("Actions → Export", "PNG, JPEG, WebP, PDF, OpenRaster, timelapse video, or share")
        )
    ),
    GuideSection(
        "Tips",
        listOf(
            GuideItem("Settings → Motion filtering", "Smooths hand tremor out of slow lines; fast strokes stay sharp"),
            GuideItem("Settings → Pressure curve", "Lighter or firmer touch for your pen"),
            GuideItem("Actions → Mirror view", "Flip the view (not the drawing) to check proportions"),
            GuideItem("Actions → Actual size", "One canvas pixel per screen pixel"),
            GuideItem("Settings → Appearance", "Dark and light themes, accent colour and interface size"),
            GuideItem("Everything else", "Gestures, the shortcut ring, keyboard keys, the top bar and toolbar, and every default: Settings")
        )
    )
)

/** One step of the tutorial: an icon, a title, and what to do. */
private class TutorialStep(val glyph: Glyph, val title: String, val body: List<String>)

private val TUTORIAL = listOf(
    TutorialStep(
        Glyph.Sparkle, "Welcome to Yume",
        listOf(
            "This tour walks through everything, one step at a time. Swipe to turn the page, use Next and Back, or jump with the dots below.",
            "Every gesture, button and default you meet here can be changed in Settings: the last step shows where.",
            "You can open this guide again any time: Guide in the gallery, or Actions in the editor."
        )
    ),
    TutorialStep(
        Glyph.Plus, "1 · Start a canvas",
        listOf(
            "In the gallery, tap New canvas. Pick a shape (screen, square, A4, …) and a resolution up to 16K, or type your own size.",
            "Your artworks appear as cards. Tap one to open it; long-press or tap … for pin, rename, duplicate, back up or delete.",
            "Each card shows when you last worked on it, its size and the time you have spent on it."
        )
    ),
    TutorialStep(
        Glyph.Gallery, "2 · The editor at a glance",
        listOf(
            "Top left: the gallery button and the artwork's name, then History, Actions and Guides.",
            "Top right: the tools (select, transform, paint, smudge, erase, fill, gradient, shape, liquify, text), then Adjustments, Layers and the colour chip.",
            "On the side: the size and opacity sliders, undo and redo. Drag the rail by its handle to move it; the lock keeps it in place."
        )
    ),
    TutorialStep(
        Glyph.Brush, "3 · Paint",
        listOf(
            "Choose Paint and draw with the pen. Pressure and tilt change size and opacity the way each brush says.",
            "Drag the top slider on the rail for size, the lower one for opacity. Slide your finger sideways off the rail while dragging for fine control.",
            "While the pen hovers, the cursor is the exact dab your stroke will start with."
        )
    ),
    TutorialStep(
        Glyph.Sparkle, "4 · Brushes",
        listOf(
            "Tap Paint again to open Brush Studio: brushes on one side, the stroke preview and settings on the other.",
            "Long-press a brush to add it to Favorites. Tap Default to make it the brush new artworks start with.",
            "More settings → Import brushes… takes Procreate, Photoshop, Clip Studio and GIMP brushes, several at once."
        )
    ),
    TutorialStep(
        Glyph.Palette, "5 · Colour",
        listOf(
            "Tap the colour chip (top right) for the wheel, triangle, square, sliders and your palette.",
            "Hold the pen still at the start of a stroke to pick the colour under it.",
            "Drag the colour chip onto the canvas to fill an area (ColorDrop). Rest before letting go and slide sideways to set how far it spreads."
        )
    ),
    TutorialStep(
        Glyph.Fit, "6 · Move around",
        listOf(
            "Two fingers pan, pinch to zoom and twist to rotate. Two fingers never draw.",
            "Actions → Fit to screen or Actual size; Mirror view flips only the view, to check proportions.",
            "Four-finger tap hides the interface for a clean look at your work."
        )
    ),
    TutorialStep(
        Glyph.Undo, "7 · Undo and history",
        listOf(
            "Two-finger tap undoes, three-finger tap redoes. The rail buttons do the same: hold to keep stepping, slide along them to scrub.",
            "History (the clock) lists every step: tap one to jump there, or delete a single step and keep the rest.",
            "Your history is saved with the artwork, so you can still undo after closing and reopening it."
        )
    ),
    TutorialStep(
        Glyph.Eraser, "8 · Erase and blend",
        listOf(
            "Erase uses brushes too: soft, hard, precise, block, airbrush, kneaded, chalk, flat and more, in Brush Studio.",
            "Smudge blends what is already painted; the wet-bleed blenders let colours run into each other like watercolour.",
            "Switching tools keeps the brush you used last in each one."
        )
    ),
    TutorialStep(
        Glyph.Layers, "9 · Layers",
        listOf(
            "Open Layers to add, duplicate, merge or delete. Long-press a layer and drag to reorder; drop it on a group to put it inside.",
            "Each layer has opacity and a blend mode (Multiply, Overlay, Screen…). Clipping paints only inside the layer below; masks hide parts without erasing.",
            "Alpha lock keeps painting inside what a layer already has."
        )
    ),
    TutorialStep(
        Glyph.Select, "10 · Select and transform",
        listOf(
            "Select: freehand, rectangle, oval, or Auto (tap a colour). Drag inside a selection to move its outline.",
            "Transform: drag to move, pinch or twist to scale and rotate, flip with the buttons, then confirm.",
            "Painting, fills and adjustments stay inside an active selection."
        )
    ),
    TutorialStep(
        Glyph.Bucket, "11 · Fill, gradient, shapes, liquify, text",
        listOf(
            "Fill: tap an area; tolerance and 'current layer only' are in its settings. Gradient: drag from start to end.",
            "Shape draws lines, rectangles, ellipses, polygons and stars with the current brush. Liquify pushes, twirls, pinches and restores.",
            "Text: tap where it goes, type, pick a font and size. Tap a tool again for its settings; with one tool's settings open, other tools open theirs."
        )
    ),
    TutorialStep(
        Glyph.Shape, "12 · QuickShape and guides",
        listOf(
            "Hold the pen still at the end of a stroke and it snaps to a clean line, arc, ellipse or polygon. Keep holding and move to adjust.",
            "Guides (the triangle): grids, perspective with vanishing points, rulers, and symmetry (mirror and radial).",
            "With Guides open, drag their handles to place them."
        )
    ),
    TutorialStep(
        Glyph.More, "13 · Shortcut ring",
        listOf(
            "Hold two fingers still on the canvas to open the shortcut ring between them (the Xiaomi pen's squeeze opens it too).",
            "Touch an item to run it: put a new finger on it, or slide one of the two fingers onto it and lift.",
            "Pick its items and their order in Settings → Gestures."
        )
    ),
    TutorialStep(
        Glyph.Sliders, "14 · Adjustments",
        listOf(
            "Hue, saturation, brightness, blur, noise, sharpen, motion blur, curves, levels, colour balance, gradient map, posterize and threshold.",
            "Drag a slider to preview live, then apply. Choose This layer or All layers.",
            "Long adjustments show a Working… card with the time left; Cancel puts everything back."
        )
    ),
    TutorialStep(
        Glyph.Share, "15 · Save and share",
        listOf(
            "Yume saves as you go; nothing to remember. If the app is closed suddenly, it recovers your work.",
            "Actions → Export: PNG, JPEG, WebP, PDF, OpenRaster (layers for other apps) or a timelapse video of your painting, with a progress bar while it works.",
            "Back up a project from its card menu; bring it back with Restore… in the gallery."
        )
    ),
    TutorialStep(
        Glyph.Wrench, "16 · Make it yours",
        listOf(
            "Everything can be changed in Settings:",
            "Appearance: dark and light themes, accent colour, interface size.  Drawing: pressure curve, motion filtering, finger painting, stroke prediction.",
            "Gestures: what every tap, hold and pen button does, and the shortcut ring.  Shortcuts: keyboard keys.",
            "Layout: the top bar's pills and the toolbar's tools.  Defaults: the tool, brushes, colour and every tool's options new artworks start with.",
            "About shows which version you have."
        )
    )
)

/** A step-by-step tour, with every gesture as a quick reference at the end. */
@Composable
fun GuideScreen(onClose: () -> Unit) {
    var reference by remember { mutableStateOf(false) }
    var section by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(interactionSource = null, indication = null) { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.94f)
                    .padding(vertical = 24.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(YumeColors.bar)
                    .clickable(interactionSource = null, indication = null) { }
                    .padding(20.dp)
            ) {
                PanelHeader(if (reference) "Quick reference" else "Guide", onClose)
                if (!reference) {
                    // Swipe between pages; Back / Next and the dots move it too.
                    val pager = androidx.compose.foundation.pager.rememberPagerState { TUTORIAL.size }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    fun go(i: Int) {
                        scope.launch { pager.animateScrollToPage(i.coerceIn(0, TUTORIAL.lastIndex)) }
                    }
                    val step = pager.currentPage
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pager,
                        modifier = Modifier.weight(1f, fill = false),
                        pageSpacing = 24.dp,
                        verticalAlignment = Alignment.Top
                    ) { page ->
                        val s = TUTORIAL[page]
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            // The page's animation: what the step is about, in motion.
                            TutorialAnimation(page, Modifier.fillMaxWidth().height(150.dp))
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(YumeColors.accentSoft),
                                    contentAlignment = Alignment.Center
                                ) { GlyphIcon(s.glyph, YumeColors.accent, size = 22.dp) }
                                Spacer(Modifier.width(12.dp))
                                Text(s.title, style = MaterialTheme.typography.titleLarge, color = YumeColors.text)
                            }
                            Spacer(Modifier.height(14.dp))
                            s.body.forEach { para ->
                                Text(para, style = MaterialTheme.typography.bodyMedium, color = YumeColors.text)
                                Spacer(Modifier.height(10.dp))
                            }
                            if (page == TUTORIAL.lastIndex) {
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallButton("Open Settings") {
                                        onClose()
                                        SettingsHost.open = true
                                    }
                                    SmallButton("Gesture reference") { reference = true }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Progress: one dot per step, tap to jump.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TUTORIAL.indices.forEach { i ->
                            Box(
                                Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (i == step) 10.dp else 7.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (i == step) YumeColors.accent else YumeColors.track)
                                    .clickable { go(i) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${step + 1} of ${TUTORIAL.size}  ·  swipe to turn the page",
                            style = MaterialTheme.typography.bodySmall,
                            color = YumeColors.textDim,
                            modifier = Modifier.weight(1f)
                        )
                        if (step > 0) {
                            SmallButton("Back") { go(step - 1) }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (step < TUTORIAL.lastIndex) SmallButton("Next") { go(step + 1) }
                        else SmallButton("Done") { onClose() }
                    }
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GUIDE.forEachIndexed { i, s ->
                            val sel = i == section
                            Text(
                                text = s.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) YumeColors.accent else YumeColors.textDim,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) YumeColors.accentSoft else YumeColors.panelAlt)
                                    .clickable { section = i }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        GUIDE[section].items.forEach { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = item.gesture,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = YumeColors.text,
                                    modifier = Modifier.width(220.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = item.action,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = YumeColors.textDim,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            DividerLine()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SmallButton("Back to the tour") { reference = false }
                }
            }
        }
    }
}

// ------------------------------------------------------------ page animations

/**
 * A small looping animation for tutorial page [page], drawn in the theme's
 * colours: a pen drawing a stroke, fingers pinching, layers trading places…
 */
@Composable
private fun TutorialAnimation(page: Int, modifier: Modifier) {
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "guide")
    val t by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(2800, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "t"
    )
    val ink = YumeColors.text
    val dim = YumeColors.textDim
    val accent = YumeColors.accent
    val panel = YumeColors.panelAlt
    val track = YumeColors.track
    androidx.compose.foundation.Canvas(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(YumeColors.panel)
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val u = h / 10f
        fun ease(x: Float) = (x * x * (3f - 2f * x)).coerceIn(0f, 1f)
        fun seg(a: Float, b: Float) = ease(((t - a) / (b - a)).coerceIn(0f, 1f))
        fun pen(x: Float, y: Float) {
            drawCircle(accent, u * 0.45f, Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.9f), u * 0.18f, Offset(x, y))
        }
        fun finger(x: Float, y: Float, down: Boolean = true) {
            drawCircle(ink.copy(alpha = if (down) 0.28f else 0.12f), u * 0.9f, Offset(x, y))
            drawCircle(ink.copy(alpha = 0.6f), u * 0.9f, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        // A wavy stroke from x0 to x0 + len, drawn up to fraction [f].
        fun stroke(x0: Float, y0: Float, len: Float, f: Float, color: Color, width: Float): Offset {
            val p = androidx.compose.ui.graphics.Path()
            val n = 48
            var last = Offset(x0, y0)
            for (i in 0..(n * f).toInt()) {
                val k = i / n.toFloat()
                val x = x0 + len * k
                val y = y0 + kotlin.math.sin(k * 6.2832f) * u * 1.4f
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                last = Offset(x, y)
            }
            drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            return last
        }
        when (page) {
            0 -> { // Welcome: sparkles breathing around the name.
                for (i in 0 until 6) {
                    val a = (i / 6f + t) * 6.2832f
                    val r = u * (3.2f + kotlin.math.sin(t * 6.2832f + i) * 0.4f)
                    drawCircle(accent.copy(alpha = 0.35f + 0.3f * kotlin.math.sin(t * 6.2832f * 2 + i).coerceAtLeast(0f)), u * 0.35f,
                        Offset(cx + kotlin.math.cos(a) * r * 1.6f, cy + kotlin.math.sin(a) * r * 0.8f))
                }
                drawRoundRect(accent, Offset(cx - u * 2.2f, cy - u * 1.2f), androidx.compose.ui.geometry.Size(u * 4.4f, u * 2.4f),
                    androidx.compose.ui.geometry.CornerRadius(u * 0.6f))
            }
            1 -> { // New canvas: a card grows out of the "+".
                val g = seg(0.1f, 0.55f)
                val cw = u * 6f * g
                val ch = u * 4.4f * g
                drawRoundRect(Color.White, Offset(cx - cw / 2f, cy - ch / 2f), androidx.compose.ui.geometry.Size(cw, ch),
                    androidx.compose.ui.geometry.CornerRadius(u * 0.4f))
                if (g < 0.2f) {
                    drawLine(accent, Offset(cx - u, cy), Offset(cx + u, cy), 6f)
                    drawLine(accent, Offset(cx, cy - u), Offset(cx, cy + u), 6f)
                }
                if (t > 0.6f) stroke(cx - u * 2.4f, cy, u * 4.8f, seg(0.6f, 0.95f), accent, u * 0.4f)
            }
            2 -> { // The editor: a highlight runs along the toolbar.
                drawRoundRect(panel, Offset(u, u), androidx.compose.ui.geometry.Size(w - 2 * u, u * 1.8f), androidx.compose.ui.geometry.CornerRadius(u))
                val n = 10
                val step = (w - 4 * u) / n
                val hot = ((t * n).toInt()) % n
                for (i in 0 until n) {
                    val x = 2 * u + step * (i + 0.5f)
                    drawCircle(if (i == hot) accent else dim, u * 0.35f, Offset(x, u * 1.9f))
                }
                drawRoundRect(panel, Offset(u, u * 3.4f), androidx.compose.ui.geometry.Size(u * 1.4f, h - u * 4.4f), androidx.compose.ui.geometry.CornerRadius(u * 0.7f))
                drawRoundRect(Color.White, Offset(u * 3.4f, u * 3.4f), androidx.compose.ui.geometry.Size(w - u * 4.4f, h - u * 4.4f), androidx.compose.ui.geometry.CornerRadius(u * 0.3f))
            }
            3 -> { // Paint: the pen draws a stroke, pressing harder in the middle.
                val f = seg(0f, 0.8f)
                val end = stroke(u * 1.5f, cy, w - u * 3f, f, accent, u * (0.3f + 0.5f * kotlin.math.sin(f * 3.1416f)))
                if (t < 0.85f) pen(end.x, end.y)
            }
            4 -> { // Brushes: a grid, one brush picked, its stroke below.
                val pick = ((t * 3).toInt()) % 6
                for (i in 0 until 6) {
                    val x = cx + (i % 3 - 1) * u * 3f
                    val y = u * 2.6f + (i / 3) * u * 2.6f
                    drawRoundRect(if (i == pick) accent.copy(alpha = 0.3f) else panel, Offset(x - u * 1.2f, y - u * 1.1f),
                        androidx.compose.ui.geometry.Size(u * 2.4f, u * 2.2f), androidx.compose.ui.geometry.CornerRadius(u * 0.4f))
                    drawCircle(ink.copy(alpha = 0.3f + 0.12f * i), u * (0.3f + 0.08f * i), Offset(x, y))
                }
            }
            5 -> { // Colour: a hue ring turns; a drop of colour lands.
                val r = u * 3.2f
                for (i in 0 until 36) {
                    val a = (i / 36f) * 6.2832f + t * 6.2832f
                    val c = Color.hsv((i * 10f) % 360f, 0.75f, 0.95f)
                    drawCircle(c, u * 0.4f, Offset(cx - u * 3f + kotlin.math.cos(a) * r, cy + kotlin.math.sin(a) * r))
                }
                val d = seg(0.3f, 0.7f)
                val c = Color.hsv((t * 360f) % 360f, 0.75f, 0.95f)
                drawCircle(c, u * (0.6f + 1.8f * seg(0.7f, 0.95f)), Offset(cx + u * 4f, cy - u * 2.5f + u * 2.5f * d))
            }
            6 -> { // Move around: two fingers pinch and twist a square.
                val s = 1f + 0.35f * kotlin.math.sin(t * 6.2832f)
                val rot = 18f * kotlin.math.sin(t * 6.2832f)
                rotate(rot, Offset(cx, cy)) {
                    val side = u * 4f * s
                    drawRect(Color.White, Offset(cx - side / 2f, cy - side / 2f), androidx.compose.ui.geometry.Size(side, side))
                }
                val a = Math.toRadians(rot.toDouble() + 45.0)
                val d = u * 3.4f * s
                finger(cx + (kotlin.math.cos(a) * d).toFloat(), cy + (kotlin.math.sin(a) * d).toFloat())
                finger(cx - (kotlin.math.cos(a) * d).toFloat(), cy - (kotlin.math.sin(a) * d).toFloat())
            }
            7 -> { // Undo: a stroke appears, a two-finger tap takes it back.
                val f = if (t < 0.5f) seg(0f, 0.45f) else 1f - seg(0.62f, 0.85f)
                stroke(u * 1.5f, cy, w - u * 3f, f, accent, u * 0.45f)
                if (t in 0.5f..0.62f) {
                    finger(cx - u * 1.2f, cy + u * 2.6f)
                    finger(cx + u * 1.2f, cy + u * 2.6f)
                }
            }
            8 -> { // Erase: a stroke, then the eraser wipes through it.
                stroke(u * 1.5f, cy, w - u * 3f, 1f, accent, u * 0.6f)
                val ex = u * 1.5f + (w - u * 3f) * seg(0.15f, 0.85f)
                drawRect(YumeColors.panel, Offset(u * 1.5f - u, 0f), androidx.compose.ui.geometry.Size(ex - u * 1.5f + u, h))
                drawRoundRect(ink.copy(alpha = 0.8f), Offset(ex - u * 0.6f, cy - u * 1.2f), androidx.compose.ui.geometry.Size(u * 1.2f, u * 2.4f),
                    androidx.compose.ui.geometry.CornerRadius(u * 0.3f))
            }
            9 -> { // Layers: three sheets; the top one slides to the bottom.
                val colors = listOf(accent, Color(0xFF5DA9F2), Color(0xFFA8D24C))
                val m = seg(0.25f, 0.75f)
                for (i in 0 until 3) {
                    val slot = if (i == 0) 2f * m else i - m
                    val y = u * 2f + slot * u * 2.2f
                    drawRoundRect(colors[i].copy(alpha = 0.85f), Offset(cx - u * 3.5f + slot * u * 0.6f, y),
                        androidx.compose.ui.geometry.Size(u * 7f, u * 1.8f), androidx.compose.ui.geometry.CornerRadius(u * 0.3f))
                }
            }
            10 -> { // Select and transform: marching dashes around a shape that moves.
                val dx = u * 3f * kotlin.math.sin(t * 6.2832f)
                drawCircle(accent, u * 1.6f, Offset(cx + dx, cy))
                drawRect(ink, Offset(cx + dx - u * 2.2f, cy - u * 2.2f), androidx.compose.ui.geometry.Size(u * 4.4f, u * 4.4f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f), t * 80f)))
            }
            11 -> { // Fill: colour floods out from the tap.
                drawRoundRect(ink.copy(alpha = 0.5f), Offset(cx - u * 4f, cy - u * 3f), androidx.compose.ui.geometry.Size(u * 8f, u * 6f),
                    androidx.compose.ui.geometry.CornerRadius(u * 0.4f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                clipRect(cx - u * 4f, cy - u * 3f, cx + u * 4f, cy + u * 3f) {
                    drawCircle(accent.copy(alpha = 0.8f), u * 6f * seg(0.15f, 0.8f), Offset(cx - u, cy))
                }
                if (t < 0.2f) finger(cx - u, cy)
            }
            12 -> { // QuickShape: a wobbly line, a pause, then it snaps straight.
                val x0 = u * 1.5f
                val x1 = w - u * 1.5f
                val snap = seg(0.6f, 0.75f)
                val p = androidx.compose.ui.graphics.Path()
                val f = seg(0f, 0.4f)
                for (i in 0..(40 * f).toInt()) {
                    val k = i / 40f
                    val y = cy + (1f - snap) * kotlin.math.sin(k * 25f) * u * 0.5f
                    if (i == 0) p.moveTo(x0 + (x1 - x0) * k, y) else p.lineTo(x0 + (x1 - x0) * k, y)
                }
                drawPath(p, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(u * 0.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                if (t in 0.4f..0.75f) {
                    pen(x0 + (x1 - x0) * f, cy)
                    drawArc(ink, -90f, 360f * seg(0.4f, 0.6f), false, Offset(x1 - u * 1.5f, cy - u * 2.4f),
                        androidx.compose.ui.geometry.Size(u * 1.2f, u * 1.2f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                }
            }
            13 -> { // Shortcut ring: two fingers hold, the ring opens, a finger slides to an item.
                val open = seg(0.15f, 0.35f)
                for (i in 0 until 6) {
                    val a = (-90f + i * 60f) * 0.017453f
                    val r = u * 3.2f * open
                    drawCircle(if (i == 1 && t > 0.7f) accent else panel, u * 0.9f * open, Offset(cx + kotlin.math.cos(a) * r, cy + kotlin.math.sin(a) * r))
                }
                val slide = seg(0.45f, 0.7f)
                val a1 = (-90f + 60f) * 0.017453f
                finger(cx - u * 0.8f, cy + u * 0.4f, t < 0.85f)
                finger(cx + u * 0.8f + (kotlin.math.cos(a1) * u * 3.2f - u * 0.8f) * slide, cy - u * 0.4f + (kotlin.math.sin(a1) * u * 3.2f + u * 0.4f) * slide, t < 0.85f)
            }
            14 -> { // Adjustments: a slider moves and the picture's hue follows.
                val v = 0.5f + 0.5f * kotlin.math.sin(t * 6.2832f)
                drawRoundRect(Color.hsv(360f * v, 0.6f, 0.9f), Offset(cx - u * 4f, u), androidx.compose.ui.geometry.Size(u * 8f, u * 5f),
                    androidx.compose.ui.geometry.CornerRadius(u * 0.4f))
                drawLine(track, Offset(cx - u * 4f, u * 8f), Offset(cx + u * 4f, u * 8f), 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(accent, u * 0.5f, Offset(cx - u * 4f + u * 8f * v, u * 8f))
            }
            15 -> { // Save and share: a progress bar fills, a tick appears.
                val p = seg(0.05f, 0.75f)
                drawRoundRect(track, Offset(cx - u * 4f, cy - u * 0.4f), androidx.compose.ui.geometry.Size(u * 8f, u * 0.8f), androidx.compose.ui.geometry.CornerRadius(u * 0.4f))
                drawRoundRect(accent, Offset(cx - u * 4f, cy - u * 0.4f), androidx.compose.ui.geometry.Size(u * 8f * p, u * 0.8f), androidx.compose.ui.geometry.CornerRadius(u * 0.4f))
                if (t > 0.78f) {
                    drawLine(accent, Offset(cx - u * 0.8f, cy - u * 2.2f), Offset(cx - u * 0.1f, cy - u * 1.5f), 8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(accent, Offset(cx - u * 0.1f, cy - u * 1.5f), Offset(cx + u * 1.2f, cy - u * 3f), 8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            else -> { // Make it yours: switches flip and a slider glides.
                for (i in 0 until 3) {
                    val y = u * 2.2f + i * u * 2.4f
                    val on = ((t * 3 + i * 0.4f) % 1f) > 0.5f
                    drawLine(dim, Offset(cx - u * 4f, y), Offset(cx + u, y), 4f)
                    drawRoundRect(if (on) accent else track, Offset(cx + u * 2f, y - u * 0.6f), androidx.compose.ui.geometry.Size(u * 2.2f, u * 1.2f),
                        androidx.compose.ui.geometry.CornerRadius(u * 0.6f))
                    drawCircle(Color.White, u * 0.5f, Offset(cx + u * (if (on) 3.6f else 2.6f), y))
                }
            }
        }
    }
}
