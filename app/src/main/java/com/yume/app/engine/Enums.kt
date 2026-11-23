package com.yume.app.engine

import android.graphics.BlendMode

/** Which tool the canvas is currently using. */
enum class Tool { Brush, Eraser, Smudge, Selection, Transform, Fill, Gradient, Shape, Liquify, Text, Clone }

/** What the transform tool's handles do: move/pinch only, corners, or a bending grid. */
enum class TransformMode(val label: String) { Free("Free"), Distort("Distort"), Warp("Warp") }

/** Shape used when dragging out a selection. */
enum class SelectionShape(val label: String) {
    Rectangle("Rect"),
    Ellipse("Oval"),
    Lasso("Freehand"),
    Automatic("Auto")
}

/** How a newly drawn selection combines with the existing one. */
enum class SelectionOp(val label: String) {
    Replace("New"),
    Add("Add"),
    Subtract("Cut")
}

/** Brush families shown in the brush browser. */
enum class BrushCategory(val label: String) {
    Brush("Brush"),
    Eraser("Eraser"),
    Smudge("Smudge")
}

/** Colour picker presentation. */
enum class ColorMode(val label: String) {
    Wheel("Wheel"),
    Triangle("Triangle"),
    Rgb("RGB"),
    Hsl("HSL"),
    Harmony("Harmony")
}

/**
 * Colour harmony schemes: the hue offsets (degrees) of every colour in the
 * scheme, in the order the swatches show them. 0 is the colour the scheme
 * is built around, placed in the middle where there is one, with its
 * partners either side.
 */
enum class Harmony(val label: String, val order: FloatArray) {
    Complementary("Opposite", floatArrayOf(0f, 180f)),
    Split("Split", floatArrayOf(-150f, 0f, 150f)),
    Analogous("Analog", floatArrayOf(-30f, 0f, 30f)),
    Triadic("Triad", floatArrayOf(-120f, 0f, 120f)),
    Tetradic("Square", floatArrayOf(-90f, 0f, 90f, 180f));

    /** Index of the colour the scheme is built around. */
    val centre: Int get() = order.indexOfFirst { it == 0f }
}

/** Layer compositing modes, exposed with Procreate-style labels. */
enum class LayerBlend(val label: String, val mode: BlendMode) {
    Normal("Normal", BlendMode.SRC_OVER),
    Multiply("Multiply", BlendMode.MULTIPLY),
    Screen("Screen", BlendMode.SCREEN),
    Overlay("Overlay", BlendMode.OVERLAY),
    Darken("Darken", BlendMode.DARKEN),
    Lighten("Lighten", BlendMode.LIGHTEN),
    ColorDodge("Color Dodge", BlendMode.COLOR_DODGE),
    ColorBurn("Color Burn", BlendMode.COLOR_BURN),
    SoftLight("Soft Light", BlendMode.SOFT_LIGHT),
    HardLight("Hard Light", BlendMode.HARD_LIGHT),
    Difference("Difference", BlendMode.DIFFERENCE),
    Exclusion("Exclusion", BlendMode.EXCLUSION),
    Hue("Hue", BlendMode.HUE),
    Saturation("Saturation", BlendMode.SATURATION),
    Color("Color", BlendMode.COLOR),
    Luminosity("Luminosity", BlendMode.LUMINOSITY),
    Add("Add", BlendMode.PLUS);

    companion object {
        fun fromName(name: String?): LayerBlend =
            entries.firstOrNull { it.name == name } ?: Normal
    }
}
