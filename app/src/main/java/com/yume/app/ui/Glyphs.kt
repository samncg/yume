package com.yume.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand drawn toolbar glyphs. Drawn on a 24x24 grid so the whole icon set shares
 * one optical weight, which is what gives the toolbar its Procreate-like feel.
 */
enum class Glyph {
    Gallery, Undo, Redo, Wrench, Wand, Select, Transform,
    Brush, Smudge, Eraser, Layers, ColorDot,
    Plus, Close, Check, ChevronDown, ChevronUp,
    Eye, EyeOff, Trash, Copy, Merge, Lock, Unlock,
    Sliders, Share, Picture, Palette, FlipH, FlipV,
    Droplet, Info, ZoomIn, ZoomOut, Fit, Rotate, Sparkle,
    Grid, Home, Clear, Save, More, History,
    Folder, Mask, Clip, Grip
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeScale: Float = 1f
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            drawGlyph(glyph, tint, strokeScale)
        }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, strokeScale: Float) {
    val stroke = Stroke(
        width = 1.7f * strokeScale,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )
    val thin = 1.4f * strokeScale

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 1.7f) {
        drawLine(tint, Offset(x1, y1), Offset(x2, y2), w * strokeScale, StrokeCap.Round)
    }

    fun path(block: Path.() -> Unit) {
        val p = Path().apply(block)
        drawPath(p, tint, style = stroke)
    }

    fun fill(block: Path.() -> Unit) {
        val p = Path().apply(block)
        drawPath(p, tint)
    }

    fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r, Offset(x, y))

    when (glyph) {
        Glyph.Gallery -> {
            for (i in 0..1) {
                for (j in 0..1) {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(3f + i * 9.5f, 3f + j * 9.5f),
                        size = Size(8f, 8f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.2f, 2.2f),
                        style = stroke
                    )
                }
            }
        }

        Glyph.Undo -> {
            path {
                moveTo(5f, 11f)
                cubicTo(6.5f, 5.5f, 14f, 3.5f, 18f, 7f)
                cubicTo(21f, 10f, 19.5f, 17f, 13f, 18.5f)
            }
            fill {
                moveTo(4.2f, 6.4f)
                lineTo(9.6f, 10.6f)
                lineTo(3.4f, 12.4f)
                close()
            }
        }

        Glyph.Redo -> {
            path {
                moveTo(19f, 11f)
                cubicTo(17.5f, 5.5f, 10f, 3.5f, 6f, 7f)
                cubicTo(3f, 10f, 4.5f, 17f, 11f, 18.5f)
            }
            fill {
                moveTo(19.8f, 6.4f)
                lineTo(14.4f, 10.6f)
                lineTo(20.6f, 12.4f)
                close()
            }
        }

        Glyph.Wrench -> {
            drawCircle(tint, 4.2f, Offset(15.5f, 8.5f), style = Stroke(thickWidth(strokeScale, 2.6f)))
            line(12.4f, 11.6f, 4.5f, 19.5f, 2.6f)
        }

        Glyph.Wand -> {
            line(4f, 20f, 15.5f, 8.5f, 2.2f)
            line(16.5f, 4f, 16.5f, 8f, 1.6f)
            line(14.5f, 6f, 18.5f, 6f, 1.6f)
            dot(20f, 12.5f, 1.2f)
        }

        Glyph.Select -> {
            // crop-style corner brackets
            path {
                moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
                moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
                moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
                moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
            }
        }

        Glyph.Transform -> {
            line(12f, 3f, 12f, 21f, 1.5f)
            line(3f, 12f, 21f, 12f, 1.5f)
            fill {
                moveTo(12f, 2.2f); lineTo(14.4f, 5.4f); lineTo(9.6f, 5.4f); close()
                moveTo(12f, 21.8f); lineTo(9.6f, 18.6f); lineTo(14.4f, 18.6f); close()
                moveTo(2.2f, 12f); lineTo(5.4f, 9.6f); lineTo(5.4f, 14.4f); close()
                moveTo(21.8f, 12f); lineTo(18.6f, 14.4f); lineTo(18.6f, 9.6f); close()
            }
        }

        Glyph.Brush -> {
            line(8.5f, 16.5f, 18f, 5.5f, 2.8f)
            fill {
                moveTo(8.9f, 15.9f)
                lineTo(4.4f, 20.2f)
                lineTo(7.1f, 19.4f)
                close()
            }
        }

        Glyph.Smudge -> {
            path {
                moveTo(12f, 3.5f)
                cubicTo(8f, 9f, 5.5f, 12f, 6.5f, 15.6f)
                cubicTo(7.5f, 19.4f, 16.5f, 19.4f, 17.5f, 15.6f)
                cubicTo(18.5f, 12f, 16f, 9f, 12f, 3.5f)
                close()
            }
            drawCircle(tint, 2.2f, Offset(12f, 14.5f), style = Stroke(thickWidth(strokeScale, 1.4f)))
        }

        Glyph.Eraser -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(6f, 8.5f),
                size = Size(13f, 8f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f, 2.4f),
                style = stroke
            )
            line(12.4f, 8.7f, 10.4f, 16.3f, 1.4f)
        }

        Glyph.Layers -> {
            fill {
                moveTo(12f, 3.4f); lineTo(20.6f, 8.2f); lineTo(12f, 13f); lineTo(3.4f, 8.2f); close()
            }
            path {
                moveTo(4.6f, 12.4f); lineTo(12f, 16.6f); lineTo(19.4f, 12.4f)
                moveTo(4.6f, 16.4f); lineTo(12f, 20.4f); lineTo(19.4f, 16.4f)
            }
        }

        Glyph.ColorDot -> {
            drawCircle(tint, 8f, Offset(12f, 12f), style = Stroke(thickWidth(strokeScale, 1.8f)))
            drawCircle(tint, 4.2f, Offset(12f, 12f))
        }

        Glyph.Plus -> {
            line(12f, 5f, 12f, 19f, 1.9f)
            line(5f, 12f, 19f, 12f, 1.9f)
        }

        Glyph.Close -> {
            line(6f, 6f, 18f, 18f, 1.9f)
            line(18f, 6f, 6f, 18f, 1.9f)
        }

        Glyph.Check -> {
            path {
                moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 6.5f)
            }
        }

        Glyph.ChevronDown -> {
            path {
                moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f)
            }
        }

        Glyph.ChevronUp -> {
            path {
                moveTo(6f, 14.5f); lineTo(12f, 8.5f); lineTo(18f, 14.5f)
            }
        }

        Glyph.Eye -> {
            path {
                moveTo(2.6f, 12f)
                cubicTo(6f, 6.6f, 18f, 6.6f, 21.4f, 12f)
                cubicTo(18f, 17.4f, 6f, 17.4f, 2.6f, 12f)
                close()
            }
            drawCircle(tint, 2.9f, Offset(12f, 12f), style = Stroke(thickWidth(strokeScale, 1.5f)))
        }

        Glyph.EyeOff -> {
            path {
                moveTo(2.6f, 12f)
                cubicTo(6f, 6.6f, 18f, 6.6f, 21.4f, 12f)
                cubicTo(18f, 17.4f, 6f, 17.4f, 2.6f, 12f)
                close()
            }
            line(4.5f, 19.5f, 19.5f, 4.5f, 1.6f)
        }

        Glyph.Trash -> {
            line(4f, 7f, 20f, 7f, 1.6f)
            path {
                moveTo(6.5f, 7f); lineTo(7.6f, 20f); lineTo(16.4f, 20f); lineTo(17.5f, 7f)
            }
            line(9.5f, 4.2f, 14.5f, 4.2f, 1.6f)
        }

        Glyph.Copy -> {
            drawRoundRect(
                tint, Offset(8.5f, 8.5f), Size(11f, 11f),
                androidx.compose.ui.geometry.CornerRadius(2.2f, 2.2f), style = stroke
            )
            path {
                moveTo(15.5f, 5.5f)
                lineTo(15.5f, 4.6f)
                quadraticTo(15.5f, 3.5f, 14.4f, 3.5f)
                lineTo(4.6f, 3.5f)
                quadraticTo(3.5f, 3.5f, 3.5f, 4.6f)
                lineTo(3.5f, 14.4f)
                quadraticTo(3.5f, 15.5f, 4.6f, 15.5f)
                lineTo(5.5f, 15.5f)
            }
        }

        Glyph.Merge -> {
            line(12f, 4f, 12f, 14f, 1.6f)
            fill {
                moveTo(12f, 17.4f); lineTo(8.4f, 12.6f); lineTo(15.6f, 12.6f); close()
            }
            line(4f, 20f, 20f, 20f, 1.6f)
        }

        Glyph.Lock -> {
            line(8.5f, 11f, 8.5f, 8.2f, 1.5f)
            line(15.5f, 11f, 15.5f, 8.2f, 1.5f)
            path {
                moveTo(8.5f, 8.2f)
                cubicTo(8.5f, 4.4f, 15.5f, 4.4f, 15.5f, 8.2f)
            }
            drawRoundRect(
                tint, Offset(5.5f, 11f), Size(13f, 9.5f),
                androidx.compose.ui.geometry.CornerRadius(2.2f, 2.2f), style = stroke
            )
        }

        Glyph.Unlock -> {
            line(8.5f, 11f, 8.5f, 8.2f, 1.5f)
            path {
                moveTo(8.5f, 8.2f)
                cubicTo(8.5f, 4.2f, 16.5f, 4.2f, 16.5f, 8.6f)
            }
            drawRoundRect(
                tint, Offset(5.5f, 11f), Size(13f, 9.5f),
                androidx.compose.ui.geometry.CornerRadius(2.2f, 2.2f), style = stroke
            )
        }

        Glyph.Sliders -> {
            line(3f, 7f, 21f, 7f, thin)
            drawCircle(tint, 2.6f, Offset(9f, 7f), style = Stroke(thickWidth(strokeScale, 1.6f)))
            line(3f, 12f, 21f, 12f, thin)
            drawCircle(tint, 2.6f, Offset(15f, 12f), style = Stroke(thickWidth(strokeScale, 1.6f)))
            line(3f, 17f, 21f, 17f, thin)
            drawCircle(tint, 2.6f, Offset(7f, 17f), style = Stroke(thickWidth(strokeScale, 1.6f)))
        }

        Glyph.Share -> {
            line(12f, 3.5f, 12f, 14.5f, 1.6f)
            fill {
                moveTo(12f, 2.4f); lineTo(15.2f, 6.2f); lineTo(8.8f, 6.2f); close()
            }
            path {
                moveTo(5f, 11.5f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 11.5f)
            }
        }

        Glyph.Picture -> {
            drawRoundRect(
                tint, Offset(3.5f, 4.5f), Size(17f, 15f),
                androidx.compose.ui.geometry.CornerRadius(2.4f, 2.4f), style = stroke
            )
            drawCircle(tint, 1.7f, Offset(9f, 9.4f))
            path {
                moveTo(5f, 17.5f); lineTo(10.5f, 12.2f); lineTo(14f, 15.2f)
                lineTo(16.4f, 13f); lineTo(19f, 15.6f)
            }
        }

        Glyph.Palette -> {
            drawCircle(tint, 8.4f, Offset(12f, 12f), style = Stroke(thickWidth(strokeScale, 1.8f)))
            dot(9f, 9.5f, 1.5f)
            dot(15f, 9.5f, 1.5f)
            dot(9f, 15f, 1.5f)
        }

        Glyph.FlipH -> {
            line(12f, 3f, 12f, 21f, 1.2f)
            fill {
                moveTo(9.4f, 6.6f); lineTo(9.4f, 17.4f); lineTo(3.4f, 12f); close()
                moveTo(14.6f, 6.6f); lineTo(20.6f, 12f); lineTo(14.6f, 17.4f); close()
            }
        }

        Glyph.FlipV -> {
            line(3f, 12f, 21f, 12f, 1.2f)
            fill {
                moveTo(6.6f, 9.4f); lineTo(17.4f, 9.4f); lineTo(12f, 3.4f); close()
                moveTo(6.6f, 14.6f); lineTo(12f, 20.6f); lineTo(17.4f, 14.6f); close()
            }
        }

        Glyph.Droplet -> {
            path {
                moveTo(12f, 3.5f)
                cubicTo(8.4f, 8.6f, 6.2f, 11.6f, 7.1f, 15f)
                cubicTo(8f, 18.4f, 16f, 18.4f, 16.9f, 15f)
                cubicTo(17.8f, 11.6f, 15.6f, 8.6f, 12f, 3.5f)
                close()
            }
        }

        Glyph.Info -> {
            drawCircle(tint, 8.4f, Offset(12f, 12f), style = Stroke(thickWidth(strokeScale, 1.8f)))
            dot(12f, 8f, 1.2f)
            line(12f, 11f, 12f, 16.5f, 1.7f)
        }

        Glyph.ZoomIn, Glyph.ZoomOut -> {
            drawCircle(tint, 6f, Offset(10.5f, 10.5f), style = Stroke(thickWidth(strokeScale, 1.8f)))
            line(15f, 15f, 20.5f, 20.5f, 2f)
            line(7.8f, 10.5f, 13.2f, 10.5f, 1.3f)
            if (glyph == Glyph.ZoomIn) line(10.5f, 7.8f, 10.5f, 13.2f, 1.3f)
        }

        Glyph.Fit -> {
            path {
                moveTo(4f, 8.5f); lineTo(4f, 4f); lineTo(8.5f, 4f)
                moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f)
                moveTo(20f, 15.5f); lineTo(20f, 20f); lineTo(15.5f, 20f)
                moveTo(8.5f, 20f); lineTo(4f, 20f); lineTo(4f, 15.5f)
            }
            drawRoundRect(
                tint, Offset(8.5f, 8.5f), Size(7f, 7f),
                androidx.compose.ui.geometry.CornerRadius(1.2f, 1.2f), style = stroke
            )
        }

        Glyph.Rotate -> {
            path {
                moveTo(19f, 12f)
                cubicTo(19f, 7.2f, 15f, 4.4f, 10.6f, 5.4f)
                cubicTo(7.4f, 6.1f, 5f, 8.8f, 5f, 12.4f)
            }
            fill {
                moveTo(20.4f, 15.2f); lineTo(17.2f, 10.4f); lineTo(21.8f, 10.2f); close()
            }
        }

        Glyph.Sparkle -> {
            fill {
                moveTo(12f, 2.5f); lineTo(13.6f, 9.2f); lineTo(20.5f, 12f)
                lineTo(13.6f, 14.8f); lineTo(12f, 21.5f)
                lineTo(10.4f, 14.8f); lineTo(3.5f, 12f)
                lineTo(10.4f, 9.2f); close()
            }
        }

        Glyph.Grid -> {
            line(4f, 9f, 20f, 9f, thin)
            line(4f, 15f, 20f, 15f, thin)
            line(9f, 4f, 9f, 20f, thin)
            line(15f, 4f, 15f, 20f, thin)
        }

        Glyph.Home -> {
            path {
                moveTo(3.5f, 11.5f); lineTo(12f, 3.8f); lineTo(20.5f, 11.5f)
            }
            path {
                moveTo(6f, 10.4f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 10.4f)
            }
        }

        Glyph.Clear -> {
            path {
                moveTo(4f, 19.5f); lineTo(20f, 19.5f)
            }
            path {
                moveTo(6.5f, 15.4f); lineTo(13.6f, 5.2f); lineTo(19.2f, 9.4f); lineTo(12.1f, 19.6f); close()
            }
        }

        Glyph.Save -> {
            path {
                moveTo(4.5f, 5.5f); lineTo(16f, 5.5f); lineTo(19.5f, 9f); lineTo(19.5f, 18.5f); lineTo(4.5f, 18.5f); close()
            }
            drawRoundRect(
                tint, Offset(8f, 5.5f), Size(7.5f, 5f),
                androidx.compose.ui.geometry.CornerRadius(1f, 1f), style = stroke
            )
            drawRoundRect(
                tint, Offset(7.5f, 13f), Size(9f, 5.5f),
                androidx.compose.ui.geometry.CornerRadius(1f, 1f), style = stroke
            )
        }

        Glyph.More -> {
            dot(6f, 12f, 1.7f)
            dot(12f, 12f, 1.7f)
            dot(18f, 12f, 1.7f)
        }

        Glyph.History -> {
            drawCircle(tint, 8f, Offset(12f, 12.5f), style = Stroke(thickWidth(strokeScale, 1.8f)))
            line(12f, 8.2f, 12f, 12.8f, 1.6f)
            line(12f, 12.8f, 15.2f, 14.4f, 1.6f)
            fill {
                moveTo(3.2f, 4.2f)
                lineTo(7.6f, 4.6f)
                lineTo(4.2f, 8.2f)
                close()
            }
        }

        Glyph.Folder -> {
            path {
                moveTo(3.5f, 7f)
                lineTo(9.4f, 7f)
                lineTo(11.2f, 9.2f)
                lineTo(20.5f, 9.2f)
                lineTo(20.5f, 18.4f)
                lineTo(3.5f, 18.4f)
                close()
            }
            line(3.5f, 11.4f, 20.5f, 11.4f, thin)
        }

        Glyph.Mask -> {
            drawRoundRect(
                tint, Offset(3.6f, 5.2f), Size(16.8f, 13.6f),
                androidx.compose.ui.geometry.CornerRadius(2.4f, 2.4f), style = stroke
            )
            drawCircle(tint, 3.6f, Offset(12f, 12f))
            line(9.6f, 12f, 14.4f, 12f, 1.4f)
            line(12f, 9.6f, 12f, 14.4f, 1.4f)
        }

        Glyph.Clip -> {
            // Two interlocking rings, the usual "clipping mask" shorthand.
            drawCircle(
                tint, 4.6f, Offset(9.4f, 12f),
                style = Stroke(thickWidth(strokeScale, 1.7f))
            )
            drawCircle(
                tint, 4.6f, Offset(14.6f, 12f),
                style = Stroke(thickWidth(strokeScale, 1.7f))
            )
        }

        Glyph.Grip -> {
            line(7f, 8f, 17f, 8f, 1.6f)
            line(7f, 12f, 17f, 12f, 1.6f)
            line(7f, 16f, 17f, 16f, 1.6f)
        }
    }
}

private fun thickWidth(strokeScale: Float, base: Float) = base * strokeScale
