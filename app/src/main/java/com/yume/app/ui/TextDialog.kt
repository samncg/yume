package com.yume.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

/** The text tool's font names, for Settings. */
internal val TEXT_FONT_LABELS: List<String> get() = FONTS.map { it.first }

/** System font families offered by the text tool: label, Android family name, preview. */
private val FONTS = listOf(
    Triple("Sans", "sans-serif", FontFamily.SansSerif),
    Triple("Serif", "serif", FontFamily.Serif),
    Triple("Mono", "monospace", FontFamily.Monospace),
    Triple("Condensed", "sans-serif-condensed", FontFamily.SansSerif),
    Triple("Casual", "casual", FontFamily.Cursive)
)

/**
 * The text tool's editor: the words, font, size, weight and alignment. The
 * text is drawn in the current colour into a new layer where the canvas was
 * tapped; move or scale it afterwards with the Transform tool.
 */
@Composable
fun TextDialog(
    onDismiss: () -> Unit,
    onAdd: (text: String, sizePx: Float, font: String, bold: Boolean, italic: Boolean, align: Int) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    // Starts from the user's text defaults (Settings, Defaults).
    val d = com.yume.app.data.Customization
    var font by rememberSaveable { mutableIntStateOf(d.tdInt("textFont", 0).coerceIn(0, FONTS.size - 1)) }
    var size by rememberSaveable { mutableFloatStateOf(d.tdFloat("textSize", 96f).coerceIn(12f, 600f)) }
    var bold by rememberSaveable { mutableStateOf(d.tdBool("textBold", false)) }
    var italic by rememberSaveable { mutableStateOf(d.tdBool("textItalic", false)) }
    var align by rememberSaveable { mutableIntStateOf(d.tdInt("textAlign", 0).coerceIn(0, 2)) }
    Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(380.dp)) {
            PanelHeader("Add text", onClose = onDismiss)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type here") },
                textStyle = TextStyle(
                    fontFamily = FONTS[font].third,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    fontSize = 20.sp,
                    color = YumeColors.text
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
            )
            Spacer(Modifier.height(10.dp))
            SegmentedRow(FONTS.map { it.first }, font, { font = it })
            Spacer(Modifier.height(8.dp))
            YumeSlider(
                value = size,
                onChange = { size = it },
                label = "Size",
                valueLabel = "${size.roundToInt()} px",
                min = 12f,
                max = 600f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (bold) "• Bold" else "Bold") { bold = !bold }
                SmallButton(if (italic) "• Italic" else "Italic") { italic = !italic }
            }
            Spacer(Modifier.height(8.dp))
            SegmentedRow(listOf("Left", "Centre", "Right"), align, { align = it })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Add text", enabled = text.isNotBlank()) {
                    onAdd(text, size, FONTS[font].second, bold, italic, align)
                }
                SmallButton("Cancel") { onDismiss() }
            }
        }
    }
}
