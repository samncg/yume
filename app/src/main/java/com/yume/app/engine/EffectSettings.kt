package com.yume.app.engine

import kotlin.math.roundToInt

/**
 * One extra setting of an effect: a slider from [min] to [max], or a choice
 * between [choices] (stored as the choice's index).
 */
class EffectSetting(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val choices: List<String>? = null,
    /** The value as shown (with the canvas's longer side, for sizes in px). */
    val format: (value: Float, longEdge: Int) -> String = { v, _ -> "${(v * 100).roundToInt()}%" }
)

/**
 * What each effect's sliders mean. Sizes are shares of the canvas's longer
 * side, so an effect looks the same on any canvas (and on the preview's
 * smaller copy).
 */
object EffectSettings {

    /** The main slider's name. */
    fun mainLabel(kind: AdjustmentKind): String = when (kind) {
        AdjustmentKind.Bloom -> "Strength"
        AdjustmentKind.Glitch -> "Amount"
        AdjustmentKind.Halftone -> "Dot size"
        AdjustmentKind.Aberration -> "Shift"
        AdjustmentKind.Mosaic -> "Block size"
        AdjustmentKind.LineArt -> "Strength"
        AdjustmentKind.Glow -> "Strength"
        AdjustmentKind.LensFlare -> "Brightness"
        AdjustmentKind.Clouds -> "Opacity"
        AdjustmentKind.Anime -> "Strength"
        else -> kind.label
    }

    /** The main slider's value as shown, for a canvas whose longer side is [longEdge]. */
    fun mainValue(kind: AdjustmentKind, amount: Float, longEdge: Int): String = when (kind) {
        AdjustmentKind.Halftone -> "${halftoneCell(amount, longEdge).roundToInt()} px"
        AdjustmentKind.Aberration -> "${aberrationShift(amount, longEdge).roundToInt()} px"
        AdjustmentKind.Mosaic -> "${mosaicBlock(amount, longEdge)} px"
        else -> "${(amount * 100).roundToInt()}%"
    }

    fun of(kind: AdjustmentKind): List<EffectSetting> = when (kind) {
        AdjustmentKind.Bloom -> listOf(
            EffectSetting("Size", 0f, 1f, 0.35f) { v, l -> "${bloomRadius(v, l)} px" },
            EffectSetting("Threshold", 0f, 0.95f, 0.55f)
        )
        AdjustmentKind.Glitch -> listOf(
            EffectSetting("Distance", 0f, 1f, 0.3f) { v, l -> "${glitchShift(v, l)} px" },
            EffectSetting("Band height", 0f, 1f, 0.3f) { v, l -> "${glitchBand(v, l)} px" },
            EffectSetting("Colour split", 0f, 1f, 0.3f) { v, l -> "${glitchSplit(v, l)} px" }
        )
        AdjustmentKind.Halftone -> listOf(
            EffectSetting("Angle", 0f, 90f, 45f) { v, _ -> "${v.roundToInt()}°" },
            EffectSetting("Ink", 0f, 1f, 0f, choices = listOf("Colour", "Black"))
        )
        AdjustmentKind.Aberration -> listOf(
            EffectSetting("Mode", 0f, 1f, 0f, choices = listOf("From centre", "One direction")),
            EffectSetting("Direction", 0f, 360f, 0f) { v, _ -> "${v.roundToInt()}°" },
            EffectSetting("Edges only", 0f, 1f, 1f)
        )
        AdjustmentKind.LineArt -> listOf(
            EffectSetting("Softness", 0f, 1f, 0.35f),
            EffectSetting("Colour", 0f, 1f, 0f, choices = listOf("Keep", "Black"))
        )
        AdjustmentKind.Glow -> listOf(
            EffectSetting("Size", 0f, 1f, 0.45f) { v, l -> "${bloomRadius(v, l)} px" }
        )
        AdjustmentKind.LensFlare -> listOf(
            EffectSetting("Across", 0f, 1f, 0.72f),
            EffectSetting("Down", 0f, 1f, 0.28f),
            EffectSetting("Size", 0f, 1f, 0.4f) { v, l -> "${flareSize(v, l).roundToInt()} px" },
            EffectSetting("Ghosts", 0f, 1f, 0.6f),
            EffectSetting("Colour", 0f, 2f, 0f, choices = listOf("Warm", "Cool", "White"))
        )
        AdjustmentKind.Clouds -> listOf(
            EffectSetting("Scale", 0f, 1f, 0.4f) { v, l -> "${cloudCell(v, l).roundToInt()} px" },
            EffectSetting("Coverage", 0f, 1f, 0.5f),
            EffectSetting("Softness", 0f, 1f, 0.5f),
            EffectSetting("Variation", 0f, 1f, 0f),
            EffectSetting("Colour", 0f, 1f, 0f, choices = listOf("White", "Storm"))
        )
        AdjustmentKind.Anime -> listOf(
            EffectSetting("Smoothing", 0f, 1f, 0.5f) { v, l -> "${animeRadius(v, l)} px" },
            EffectSetting("Vividness", 0f, 1f, 0.5f)
        )
        else -> emptyList()
    }

    fun defaults(kind: AdjustmentKind): FloatArray = of(kind).map { it.default }.toFloatArray()

    /** Settings at their largest reach (for the work area an effect may touch). */
    fun widest(kind: AdjustmentKind): FloatArray = of(kind).map { it.max }.toFloatArray()

    // ------------------------------------------------------------ geometry

    private fun sq(v: Float) = v.coerceIn(0f, 1f).let { it * it }

    fun bloomRadius(size: Float, longEdge: Int): Int = (2f + sq(size) * 0.02f * longEdge).roundToInt().coerceAtLeast(1)
    fun glitchShift(distance: Float, longEdge: Int): Int = (distance.coerceIn(0f, 1f) * 0.12f * longEdge).roundToInt().coerceAtLeast(1)
    fun glitchBand(height: Float, longEdge: Int): Int = ((0.002f + sq(height) * 0.05f) * longEdge).roundToInt().coerceAtLeast(1)
    fun glitchSplit(split: Float, longEdge: Int): Int = (split.coerceIn(0f, 1f) * 0.015f * longEdge).roundToInt()
    fun halftoneCell(amount: Float, longEdge: Int): Float = 3f + sq(amount) * 0.03f * longEdge
    fun aberrationShift(amount: Float, longEdge: Int): Float = amount.coerceIn(0f, 1f) * 0.015f * longEdge
    fun mosaicBlock(amount: Float, longEdge: Int): Int = (2f + sq(amount) * 0.04f * longEdge).roundToInt().coerceAtLeast(2)
    fun flareSize(size: Float, longEdge: Int): Float = (0.03f + sq(size) * 0.3f) * longEdge
    fun cloudCell(scale: Float, longEdge: Int): Float = (0.02f + sq(scale) * 0.5f) * longEdge
    fun animeRadius(smoothing: Float, longEdge: Int): Int = (1f + sq(smoothing) * 0.004f * longEdge).roundToInt().coerceAtLeast(1)
}
