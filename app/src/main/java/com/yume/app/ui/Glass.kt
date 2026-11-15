package com.yume.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.yume.app.engine.CanvasGlass

/** The backdrop glass surfaces sample. The editor provides the canvas proxy. */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * Backdrop that draws the live canvas proxy ([CanvasGlass]) in window space,
 * shifted by the glass element's own window position, so every glass surface
 * sees exactly the part of the painting that lies behind it.
 *
 * [version] is read while drawing, which makes the glass redraw whenever the
 * render thread publishes a new frame. [canvasOrigin] is where the canvas
 * surface sits in the window.
 */
class CanvasProxyBackdrop(
    private val version: State<Int>,
    private val canvasOrigin: State<Offset>
) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        version.value
        val bmp = CanvasGlass.frame ?: return
        val pos = coordinates?.positionInWindow() ?: Offset.Zero
        val origin = canvasOrigin.value
        translate(origin.x - pos.x, origin.y - pos.y) {
            drawImage(
                image = bmp.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(bmp.width * CanvasGlass.SCALE, bmp.height * CanvasGlass.SCALE),
                filterQuality = FilterQuality.Low
            )
        }
    }
}

/** How much a glass surface frosts what is behind it. */
enum class GlassWeight(val blurDp: Float, private val darkTint: Color, private val lightTint: Color) {
    /** Toolbar capsules and small buttons: the canvas stays readable through them. */
    Light(14f, Color(0xB0141418), Color(0xB8F6F6F8)),
    /** Panels with text and controls: more blur and a darker veil for contrast. */
    Heavy(28f, Color(0xD0121216), Color(0xDCF7F7FA));

    /** The veil for the theme in use (pale on light themes). */
    val tint: Color get() = if (YumeColors.light) lightTint else darkTint
}

/**
 * Liquid glass surface: vibrancy, blur and a lens refraction at the rim
 * (Android 13+; older versions keep the blur and veil), with the library's
 * default edge highlight and shadow.
 */
@Composable
fun Modifier.glass(
    corner: Float,
    weight: GlassWeight = GlassWeight.Light
): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val shape = RoundedCornerShape(corner.dp)
    if (weight == GlassWeight.Light) {
        // Bars and capsules: plain frosted glass, a little see-through. No
        // lens at the rim and no drop shadow (its soft square read as a
        // smudge around the toolbar).
        return this.drawPlainBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(14.dp.toPx()) },
            onDrawSurface = { drawRect(weight.tint) }
        )
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(weight.blurDp.dp.toPx())
            // The refraction band must fit inside the corner radius; strong
            // chromatic aberration splits colours along the rim.
            val h = (corner * 0.7f).dp.toPx()
            lens(h, h * 2.4f, depthEffect = true, chromaticAberration = true)
        },
        onDrawSurface = { drawRect(weight.tint) }
    )
}
