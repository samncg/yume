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
            GuideItem("Two-finger hold", "Shortcut ring (the same menu as the Xiaomi pen's squeeze)"),
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
            GuideItem("More settings → Import brushes…", "Procreate .brush / .brushset, Photoshop .abr, Clip Studio .sut, GIMP .gbr, images"),
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
            GuideItem("Settings → Appearance", "Background, accent colour and interface size")
        )
    )
)

/** Every gesture and the less obvious parts of the interface, in one place. */
@Composable
fun GuideScreen(onClose: () -> Unit) {
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
                PanelHeader("Gestures & tips", onClose)
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
            }
        }
    }
}
