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
enum class GlassWeight(val blurDp: Float, val tint: Color) {
    /** Toolbar capsules and small buttons: the canvas stays readable through them. */
    Light(6f, Color(0x4A111116)),
    /** Panels with text and controls: more blur and a darker veil for contrast. */
    Heavy(28f, Color(0xD0121216))
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
