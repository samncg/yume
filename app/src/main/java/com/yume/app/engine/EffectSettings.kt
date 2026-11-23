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
}
