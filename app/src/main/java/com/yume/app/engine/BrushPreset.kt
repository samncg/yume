package com.yume.app.engine

import org.json.JSONObject

/**
 * A painting brush description. Everything here is data so presets can be
 * serialised, edited in the brush studio and cloned cheaply.
 */
data class BrushPreset(
    val id: String,
    val name: String,
    val category: BrushCategory = BrushCategory.Brush,
    /** Nominal brush diameter in document pixels. */
    val size: Float = 24f,
    /** Maximum coverage of a single stamp (0..1). */
    val opacity: Float = 1f,
    /** How much paint each stamp deposits (0..1). */
    val flow: Float = 1f,
    /** 0 = fully soft airbrush, 1 = crisp edge. */
    val hardness: Float = 0.85f,
    /** Stamp interval as a fraction of the brush diameter. Small = smooth. */
    val spacing: Float = 0.06f,
    /** Input smoothing / streamline (0..1). */
    val smoothing: Float = 0.4f,
    /** Amount of alpha texture baked into the dab (0..1). */
    val grain: Float = 0f,
    /** Random position jitter, in brush diameters (0..1). */
    val jitter: Float = 0f,
    /** How strongly pressure scales size (0..1). */
    val pressureSize: Float = 0.85f,
    /** How strongly pressure scales opacity (0..1). */
    val pressureOpacity: Float = 0.35f,
    /** How strongly stylus tilt enlarges the brush (0..1). */
    val tiltSize: Float = 0f,
    /** Strength used by the smudge engine (0..1). */
    val smudgeAmount: Float = 0.4f,
    /** Extra soft bloom around the dab (used by glow-style brushes). */
    val glow: Boolean = false,
    /** Imported stamp shape ([BrushAssets]); empty = the round dab. */
    val shapeId: String = "",
    /** Imported grain texture ([BrushAssets]); empty = procedural noise. */
    val grainId: String = "",
    /** Stamp rotation: 0 fixed, 1 follows the stroke, 2 follows pen tilt, 3 random. */
    val rotationMode: Int = 0,
    /** Base stamp angle in degrees (added to the mode's angle). */
    val rotation: Float = 0f,
    /** Wet mix: how much colour each dab picks up from the layer (0..1). */
    val wetMix: Float = 0f,
    /** Built-in presets can't be deleted. */
    val builtIn: Boolean = true
) {
    /** Cache key for the procedurally generated dab bitmap. */
    val dabKey: String
        get() = "$hardness|$grain|$glow|$shapeId|$grainId"

    fun copyForStroke(): BrushPreset = this

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category.name)
        put("size", size.toDouble())
        put("opacity", opacity.toDouble())
        put("flow", flow.toDouble())
        put("hardness", hardness.toDouble())
        put("spacing", spacing.toDouble())
        put("smoothing", smoothing.toDouble())
        put("grain", grain.toDouble())
        put("jitter", jitter.toDouble())
        put("pressureSize", pressureSize.toDouble())
        put("pressureOpacity", pressureOpacity.toDouble())
        put("tiltSize", tiltSize.toDouble())
        put("smudgeAmount", smudgeAmount.toDouble())
        put("glow", glow)
        put("shapeId", shapeId)
        put("grainId", grainId)
        put("rotationMode", rotationMode)
        put("rotation", rotation.toDouble())
        put("wetMix", wetMix.toDouble())
        put("builtIn", builtIn)
    }

    companion object {
        fun fromJson(o: JSONObject, base: BrushPreset): BrushPreset = base.copy(
            id = o.optString("id", base.id),
            category = runCatching {
                BrushCategory.valueOf(o.optString("category", base.category.name))
            }.getOrDefault(base.category),
            builtIn = o.optBoolean("builtIn", base.builtIn),
            name = o.optString("name", base.name),
            size = o.optDouble("size", base.size.toDouble()).toFloat(),
            opacity = o.optDouble("opacity", base.opacity.toDouble()).toFloat(),
            flow = o.optDouble("flow", base.flow.toDouble()).toFloat(),
            hardness = o.optDouble("hardness", base.hardness.toDouble()).toFloat(),
            spacing = o.optDouble("spacing", base.spacing.toDouble()).toFloat(),
            smoothing = o.optDouble("smoothing", base.smoothing.toDouble()).toFloat(),
            grain = o.optDouble("grain", base.grain.toDouble()).toFloat(),
            jitter = o.optDouble("jitter", base.jitter.toDouble()).toFloat(),
            pressureSize = o.optDouble("pressureSize", base.pressureSize.toDouble()).toFloat(),
            pressureOpacity = o.optDouble("pressureOpacity", base.pressureOpacity.toDouble()).toFloat(),
            tiltSize = o.optDouble("tiltSize", base.tiltSize.toDouble()).toFloat(),
            smudgeAmount = o.optDouble("smudgeAmount", base.smudgeAmount.toDouble()).toFloat(),
            glow = o.optBoolean("glow", base.glow),
            shapeId = o.optString("shapeId", base.shapeId),
            grainId = o.optString("grainId", base.grainId),
            rotationMode = o.optInt("rotationMode", base.rotationMode).coerceIn(0, 3),
            rotation = o.optDouble("rotation", base.rotation.toDouble()).toFloat(),
            wetMix = o.optDouble("wetMix", base.wetMix.toDouble()).toFloat().coerceIn(0f, 1f)
        )
    }
}

/** Built-in brush library. */
object BrushLibrary {

    val defaults: List<BrushPreset> = listOf(
        BrushPreset(
            id = "studio-pen", name = "Studio Pen", size = 14f, hardness = 0.95f,
            spacing = 0.05f, smoothing = 0.45f, pressureSize = 0.75f, pressureOpacity = 0.15f
        ),
        BrushPreset(
            id = "technical-pen", name = "Technical Pen", size = 8f, hardness = 1f,
            flow = 1f, spacing = 0.04f, smoothing = 0.6f, pressureSize = 0.55f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "gel-pen", name = "Gel Pen", size = 10f, hardness = 0.9f, flow = 0.9f,
            spacing = 0.04f, smoothing = 0.5f, pressureSize = 0.7f, pressureOpacity = 0.2f
        ),
        BrushPreset(
            id = "pencil-6b", name = "6B Pencil", size = 16f, hardness = 0.55f, flow = 0.75f,
            spacing = 0.1f, smoothing = 0.3f, grain = 0.5f, jitter = 0.04f,
            pressureSize = 0.8f, pressureOpacity = 0.5f, tiltSize = 0.15f
        ),
        BrushPreset(
            id = "soft-brush", name = "Soft Brush", size = 60f, hardness = 0.12f, flow = 0.8f,
            spacing = 0.05f, smoothing = 0.35f, pressureSize = 0.6f, pressureOpacity = 0.4f
        ),
        BrushPreset(
            id = "airbrush", name = "Airbrush", size = 90f, hardness = 0.05f, flow = 0.25f,
            spacing = 0.035f, smoothing = 0.3f, pressureSize = 0.4f, pressureOpacity = 0.7f
        ),
        BrushPreset(
            id = "charcoal", name = "Charcoal", size = 30f, hardness = 0.35f, flow = 0.7f,
            spacing = 0.09f, smoothing = 0.25f, grain = 0.8f, jitter = 0.12f,
            pressureSize = 0.7f, pressureOpacity = 0.4f, tiltSize = 0.35f
        ),
        BrushPreset(
            id = "ink-bleed", name = "Ink Bleed", size = 18f, hardness = 0.7f, flow = 0.65f,
            spacing = 0.05f, smoothing = 0.3f, grain = 0.25f, pressureSize = 0.7f, pressureOpacity = 0.3f
        ),
        BrushPreset(
            id = "watercolor", name = "Watercolor Wash", size = 70f, hardness = 0.2f, flow = 0.16f,
            spacing = 0.03f, smoothing = 0.4f, grain = 0.35f, pressureSize = 0.6f, pressureOpacity = 0.8f,
            wetMix = 0.6f
        ),
        BrushPreset(
            id = "oil-paint", name = "Oil Paint", size = 40f, hardness = 0.6f, flow = 0.9f,
            spacing = 0.06f, smoothing = 0.2f, grain = 0.2f, jitter = 0.05f,
            pressureSize = 0.6f, pressureOpacity = 0.3f, tiltSize = 0.25f,
            wetMix = 0.45f, rotationMode = 1
        ),
        BrushPreset(
            id = "marker", name = "Marker", size = 26f, hardness = 0.8f, flow = 0.7f,
            spacing = 0.05f, smoothing = 0.5f, pressureSize = 0.3f, pressureOpacity = 0.1f
        ),
        BrushPreset(
            id = "spray", name = "Spray Paint", size = 50f, hardness = 0.3f, flow = 0.3f,
            spacing = 0.02f, smoothing = 0.25f, grain = 0.6f, jitter = 0.6f,
            pressureSize = 0.4f, pressureOpacity = 0.5f
        ),
        BrushPreset(
            id = "eraser-soft", name = "Soft Eraser", category = BrushCategory.Eraser,
            size = 55f, hardness = 0.25f, flow = 0.55f, spacing = 0.05f,
            smoothing = 0.4f, pressureSize = 0.7f, pressureOpacity = 0.6f
        ),
        BrushPreset(
            id = "eraser-hard", name = "Hard Eraser", category = BrushCategory.Eraser,
            size = 26f, hardness = 1f, flow = 1f, spacing = 0.05f,
            smoothing = 0.5f, pressureSize = 0.6f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "smudge", name = "Smudge", category = BrushCategory.Smudge,
            size = 45f, hardness = 0.5f, flow = 0.6f, spacing = 0.03f,
            smoothing = 0.5f, smudgeAmount = 0.35f, pressureSize = 0.5f, pressureOpacity = 0.5f
        )
    )

    fun preset(id: String): BrushPreset = defaults.firstOrNull { it.id == id } ?: defaults.first()

    fun byCategory(category: BrushCategory): List<BrushPreset> =
        defaults.filter { it.category == category }

    fun defaultFor(category: BrushCategory): BrushPreset =
        byCategory(category).first()
}
