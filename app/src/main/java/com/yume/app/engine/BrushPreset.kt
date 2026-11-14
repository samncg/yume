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
    /** How strongly stylus tilt lightens the brush (0..1): pencil shading. */
    val tiltOpacity: Float = 0f,
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
    val builtIn: Boolean = true,
    /** Library group in Brush Studio ("Sketching", "Inking", "Imported"…). */
    val group: String = ""
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
        put("tiltOpacity", tiltOpacity.toDouble())
        put("smudgeAmount", smudgeAmount.toDouble())
        put("glow", glow)
        put("shapeId", shapeId)
        put("grainId", grainId)
        put("rotationMode", rotationMode)
        put("rotation", rotation.toDouble())
        put("wetMix", wetMix.toDouble())
        put("builtIn", builtIn)
        put("group", group)
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
            // Built-ins saved before tilt shading existed take the new tilt
            // defaults; anything the user set on their own presets stays.
            tiltSize = if (!o.has("tiltOpacity") && o.optBoolean("builtIn", base.builtIn)) base.tiltSize
            else o.optDouble("tiltSize", base.tiltSize.toDouble()).toFloat(),
            tiltOpacity = o.optDouble("tiltOpacity", base.tiltOpacity.toDouble()).toFloat(),
            smudgeAmount = o.optDouble("smudgeAmount", base.smudgeAmount.toDouble()).toFloat(),
            glow = o.optBoolean("glow", base.glow),
            shapeId = o.optString("shapeId", base.shapeId),
            grainId = o.optString("grainId", base.grainId),
            rotationMode = o.optInt("rotationMode", base.rotationMode).coerceIn(0, 3),
            rotation = o.optDouble("rotation", base.rotation.toDouble()).toFloat(),
            wetMix = o.optDouble("wetMix", base.wetMix.toDouble()).toFloat().coerceIn(0f, 1f),
            // Presets saved before groups existed: built-ins take their
            // library group, the user's own land in "Custom".
            group = o.optString("group", "").ifEmpty {
                if (o.optBoolean("builtIn", base.builtIn) && o.optString("id") == base.id) base.group else "Custom"
            }
        )
    }
}

/** Built-in brush library. */
object BrushLibrary {

    val defaults: List<BrushPreset> = listOf(
        BrushPreset(
            id = "studio-pen", group = "Inking", name = "Studio Pen", size = 14f, hardness = 0.95f,
            spacing = 0.05f, smoothing = 0.45f, pressureSize = 0.75f, pressureOpacity = 0.15f
        ),
        BrushPreset(
            id = "technical-pen", group = "Inking", name = "Technical Pen", size = 8f, hardness = 1f,
            flow = 1f, spacing = 0.04f, smoothing = 0.6f, pressureSize = 0.55f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "gel-pen", group = "Inking", name = "Gel Pen", size = 10f, hardness = 0.9f, flow = 0.9f,
            spacing = 0.04f, smoothing = 0.5f, pressureSize = 0.7f, pressureOpacity = 0.2f
        ),
        BrushPreset(
            id = "pencil-6b", group = "Sketching", name = "6B Pencil", size = 16f, hardness = 0.55f, flow = 0.75f,
            spacing = 0.1f, smoothing = 0.3f, grain = 0.5f, jitter = 0.04f,
            pressureSize = 0.8f, pressureOpacity = 0.5f, tiltSize = 0.6f, tiltOpacity = 0.5f
        ),
        BrushPreset(
            id = "soft-brush", group = "Airbrushing", name = "Soft Brush", size = 60f, hardness = 0.12f, flow = 0.8f,
            spacing = 0.05f, smoothing = 0.35f, pressureSize = 0.6f, pressureOpacity = 0.4f,
            tiltSize = 0.4f, tiltOpacity = 0.3f
        ),
        BrushPreset(
            id = "airbrush", group = "Airbrushing", name = "Airbrush", size = 90f, hardness = 0.05f, flow = 0.25f,
            spacing = 0.035f, smoothing = 0.3f, pressureSize = 0.4f, pressureOpacity = 0.7f,
            tiltSize = 0.5f, tiltOpacity = 0.3f
        ),
        BrushPreset(
            id = "charcoal", group = "Sketching", name = "Charcoal", size = 30f, hardness = 0.35f, flow = 0.7f,
            spacing = 0.09f, smoothing = 0.25f, grain = 0.8f, jitter = 0.12f,
            pressureSize = 0.7f, pressureOpacity = 0.4f, tiltSize = 0.6f, tiltOpacity = 0.4f
        ),
        BrushPreset(
            id = "ink-bleed", group = "Inking", name = "Ink Bleed", size = 18f, hardness = 0.7f, flow = 0.65f,
            spacing = 0.05f, smoothing = 0.3f, grain = 0.25f, pressureSize = 0.7f, pressureOpacity = 0.3f
        ),
        BrushPreset(
            id = "watercolor", group = "Painting", name = "Watercolor Wash", size = 70f, hardness = 0.2f, flow = 0.16f,
            spacing = 0.03f, smoothing = 0.4f, grain = 0.35f, pressureSize = 0.6f, pressureOpacity = 0.8f,
            wetMix = 0.6f
        ),
        BrushPreset(
            id = "oil-paint", group = "Painting", name = "Oil Paint", size = 40f, hardness = 0.6f, flow = 0.9f,
            spacing = 0.06f, smoothing = 0.2f, grain = 0.2f, jitter = 0.05f,
            pressureSize = 0.6f, pressureOpacity = 0.3f, tiltSize = 0.25f,
            wetMix = 0.45f, rotationMode = 1
        ),
        BrushPreset(
            id = "marker", group = "Inking", name = "Marker", size = 26f, hardness = 0.8f, flow = 0.7f,
            spacing = 0.05f, smoothing = 0.5f, pressureSize = 0.3f, pressureOpacity = 0.1f
        ),
        BrushPreset(
            id = "spray", group = "Textures", name = "Spray Paint", size = 50f, hardness = 0.3f, flow = 0.3f,
            spacing = 0.02f, smoothing = 0.25f, grain = 0.6f, jitter = 0.6f,
            pressureSize = 0.4f, pressureOpacity = 0.5f, tiltSize = 0.4f
        ),
        BrushPreset(
            id = "eraser-soft", group = "Erasers", name = "Soft Eraser", category = BrushCategory.Eraser,
            size = 55f, hardness = 0.25f, flow = 0.55f, spacing = 0.05f,
            smoothing = 0.4f, pressureSize = 0.7f, pressureOpacity = 0.6f
        ),
        BrushPreset(
            id = "eraser-hard", group = "Erasers", name = "Hard Eraser", category = BrushCategory.Eraser,
            size = 26f, hardness = 1f, flow = 1f, spacing = 0.05f,
            smoothing = 0.5f, pressureSize = 0.6f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "smudge", group = "Blending", name = "Smudge", category = BrushCategory.Smudge,
            size = 45f, hardness = 0.5f, flow = 0.6f, spacing = 0.03f,
            smoothing = 0.5f, smudgeAmount = 0.35f, pressureSize = 0.5f, pressureOpacity = 0.5f
        ),
        // ---------------------------------------------------- sketching
        BrushPreset(
            id = "pencil-hb", group = "Sketching", name = "HB Pencil", size = 8f, hardness = 0.6f, flow = 0.8f,
            spacing = 0.08f, smoothing = 0.3f, grain = 0.35f, grainId = "yume-paper", jitter = 0.02f,
            pressureSize = 0.6f, pressureOpacity = 0.6f, tiltSize = 0.6f, tiltOpacity = 0.5f
        ),
        BrushPreset(
            id = "pencil-2h", group = "Sketching", name = "2H Pencil", size = 6f, opacity = 0.7f, hardness = 0.7f,
            flow = 0.7f, spacing = 0.08f, smoothing = 0.3f, grain = 0.5f, grainId = "yume-paper",
            pressureSize = 0.5f, pressureOpacity = 0.7f, tiltSize = 0.5f, tiltOpacity = 0.5f
        ),
        BrushPreset(
            id = "pencil-mech", group = "Sketching", name = "Mechanical Pencil", size = 4f, hardness = 0.85f,
            flow = 0.85f, spacing = 0.06f, smoothing = 0.35f, grain = 0.2f, grainId = "yume-paper",
            pressureSize = 0.3f, pressureOpacity = 0.6f
        ),
        BrushPreset(
            id = "graphite-shade", group = "Sketching", name = "Graphite Shading", size = 70f, hardness = 0.3f,
            flow = 0.25f, spacing = 0.06f, smoothing = 0.25f, grain = 0.65f, grainId = "yume-paper",
            pressureSize = 0.4f, pressureOpacity = 0.7f, tiltSize = 0.8f, tiltOpacity = 0.6f
        ),
        BrushPreset(
            id = "charcoal-stick", group = "Sketching", name = "Charcoal Stick", size = 40f, hardness = 0.5f,
            flow = 0.75f, spacing = 0.1f, smoothing = 0.2f, grain = 0.75f, grainId = "yume-paper",
            shapeId = "yume-chalk", rotationMode = 3, pressureSize = 0.6f, pressureOpacity = 0.5f, tiltSize = 0.6f
        ),
        BrushPreset(
            id = "soft-pastel", group = "Sketching", name = "Soft Pastel", size = 50f, hardness = 0.5f, flow = 0.6f,
            spacing = 0.09f, smoothing = 0.25f, grain = 0.7f, grainId = "yume-canvas", shapeId = "yume-chalk",
            rotationMode = 3, pressureSize = 0.5f, pressureOpacity = 0.6f, tiltSize = 0.5f, tiltOpacity = 0.3f
        ),
        // ---------------------------------------------------- inking
        BrushPreset(
            id = "fine-liner", group = "Inking", name = "Fine Liner", size = 4f, hardness = 1f, spacing = 0.04f,
            smoothing = 0.5f, pressureSize = 0.2f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "brush-pen", group = "Inking", name = "Brush Pen", size = 20f, hardness = 0.95f, spacing = 0.04f,
            smoothing = 0.55f, pressureSize = 1f, pressureOpacity = 0.05f
        ),
        BrushPreset(
            id = "crow-quill", group = "Inking", name = "Crow Quill", size = 10f, hardness = 1f, spacing = 0.03f,
            smoothing = 0.6f, pressureSize = 1f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "dry-ink", group = "Inking", name = "Dry Ink", size = 16f, hardness = 0.85f, flow = 0.85f,
            spacing = 0.05f, smoothing = 0.45f, grain = 0.5f, grainId = "yume-paper", jitter = 0.03f,
            pressureSize = 0.8f, pressureOpacity = 0.2f
        ),
        BrushPreset(
            id = "sumi-ink", group = "Inking", name = "Sumi Ink", size = 30f, hardness = 0.75f, flow = 0.85f,
            spacing = 0.04f, smoothing = 0.4f, grain = 0.2f, pressureSize = 1f, pressureOpacity = 0.25f, wetMix = 0.1f
        ),
        BrushPreset(
            id = "ballpoint", group = "Inking", name = "Ballpoint", size = 5f, flow = 0.85f, hardness = 0.9f,
            spacing = 0.04f, smoothing = 0.45f, grain = 0.15f, pressureSize = 0.3f, pressureOpacity = 0.5f
        ),
        // ---------------------------------------------------- calligraphy
        BrushPreset(
            id = "flat-nib", group = "Calligraphy", name = "Flat Nib", size = 30f, hardness = 0.95f, spacing = 0.02f,
            smoothing = 0.55f, shapeId = "yume-flat", rotation = 45f, pressureSize = 0.4f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "chisel-marker", group = "Calligraphy", name = "Chisel Marker", size = 34f, hardness = 0.9f, flow = 0.7f,
            spacing = 0.03f, smoothing = 0.5f, shapeId = "yume-flat", rotation = 30f, pressureSize = 0.2f, pressureOpacity = 0.1f
        ),
        BrushPreset(
            id = "script-brush", group = "Calligraphy", name = "Script Brush", size = 24f, hardness = 0.95f,
            spacing = 0.03f, smoothing = 0.7f, pressureSize = 1f, pressureOpacity = 0f
        ),
        BrushPreset(
            id = "monoline", group = "Calligraphy", name = "Monoline", size = 12f, hardness = 1f, spacing = 0.03f,
            smoothing = 0.8f, pressureSize = 0f, pressureOpacity = 0f
        ),
        // ---------------------------------------------------- painting
        BrushPreset(
            id = "round-brush", group = "Painting", name = "Round Brush", size = 40f, hardness = 0.7f, flow = 0.9f,
            spacing = 0.05f, smoothing = 0.3f, pressureSize = 0.7f, pressureOpacity = 0.3f, wetMix = 0.2f
        ),
        BrushPreset(
            id = "flat-brush", group = "Painting", name = "Flat Brush", size = 60f, hardness = 0.8f, flow = 0.85f,
            spacing = 0.04f, smoothing = 0.3f, shapeId = "yume-bristle", rotationMode = 1, rotation = 90f,
            pressureSize = 0.5f, pressureOpacity = 0.3f, wetMix = 0.3f
        ),
        BrushPreset(
            id = "gouache", group = "Painting", name = "Gouache", size = 45f, hardness = 0.65f, flow = 0.95f,
            spacing = 0.05f, smoothing = 0.3f, grain = 0.15f, grainId = "yume-paper", pressureSize = 0.6f,
            pressureOpacity = 0.2f, wetMix = 0.25f
        ),
        BrushPreset(
            id = "acrylic", group = "Painting", name = "Acrylic", size = 55f, hardness = 0.75f, flow = 0.9f,
            spacing = 0.04f, smoothing = 0.25f, grain = 0.4f, grainId = "yume-canvas", shapeId = "yume-bristle",
            rotationMode = 1, rotation = 90f, pressureSize = 0.5f, pressureOpacity = 0.3f, wetMix = 0.35f
        ),
        BrushPreset(
            id = "impasto", group = "Painting", name = "Impasto", size = 50f, hardness = 0.5f, flow = 0.95f,
            spacing = 0.05f, smoothing = 0.2f, grain = 0.35f, grainId = "yume-canvas", jitter = 0.05f,
            rotationMode = 1, pressureSize = 0.5f, pressureOpacity = 0.2f, wetMix = 0.5f
        ),
        BrushPreset(
            id = "watercolor-round", group = "Painting", name = "Watercolor Round", size = 40f, hardness = 0.35f,
            flow = 0.2f, spacing = 0.03f, smoothing = 0.4f, grain = 0.3f, grainId = "yume-paper",
            pressureSize = 0.7f, pressureOpacity = 0.7f, wetMix = 0.7f
        ),
        BrushPreset(
            id = "wet-glaze", group = "Painting", name = "Wet Glaze", size = 120f, hardness = 0.1f, flow = 0.08f,
            spacing = 0.03f, smoothing = 0.4f, pressureSize = 0.4f, pressureOpacity = 0.8f, wetMix = 0.5f
        ),
        // ---------------------------------------------------- airbrushing
        BrushPreset(
            id = "hard-airbrush", group = "Airbrushing", name = "Hard Airbrush", size = 70f, hardness = 0.6f,
            flow = 0.3f, spacing = 0.04f, smoothing = 0.3f, pressureSize = 0.3f, pressureOpacity = 0.7f
        ),
        BrushPreset(
            id = "fine-airbrush", group = "Airbrushing", name = "Fine Airbrush", size = 20f, hardness = 0.1f,
            flow = 0.2f, spacing = 0.04f, smoothing = 0.35f, pressureSize = 0.5f, pressureOpacity = 0.7f
        ),
        BrushPreset(
            id = "mist", group = "Airbrushing", name = "Mist", size = 220f, hardness = 0f, flow = 0.05f,
            spacing = 0.04f, smoothing = 0.3f, pressureSize = 0.3f, pressureOpacity = 0.8f
        ),
        BrushPreset(
            id = "glow", group = "Airbrushing", name = "Glow", size = 50f, hardness = 0.4f, flow = 0.3f,
            spacing = 0.04f, smoothing = 0.4f, glow = true, pressureSize = 0.5f, pressureOpacity = 0.5f
        ),
        // ---------------------------------------------------- textures
        BrushPreset(
            id = "splatter", group = "Textures", name = "Splatter", size = 90f, hardness = 0.9f, spacing = 0.5f,
            smoothing = 0.1f, jitter = 0.4f, shapeId = "yume-splatter", rotationMode = 3,
            pressureSize = 0.6f, pressureOpacity = 0.2f
        ),
        BrushPreset(
            id = "stipple", group = "Textures", name = "Stipple", size = 36f, hardness = 0.9f, spacing = 0.6f,
            smoothing = 0.1f, jitter = 0.3f, shapeId = "yume-splatter", rotationMode = 3,
            pressureSize = 0.5f, pressureOpacity = 0.4f
        ),
        BrushPreset(
            id = "sponge", group = "Textures", name = "Sponge", size = 80f, hardness = 0.5f, flow = 0.6f,
            spacing = 0.2f, smoothing = 0.2f, grain = 0.9f, grainId = "yume-sponge", rotationMode = 3,
            jitter = 0.1f, pressureSize = 0.4f, pressureOpacity = 0.6f
        ),
        BrushPreset(
            id = "canvas-texture", group = "Textures", name = "Canvas Texture", size = 120f, hardness = 0.3f,
            flow = 0.4f, spacing = 0.05f, smoothing = 0.2f, grain = 0.8f, grainId = "yume-canvas",
            pressureSize = 0.3f, pressureOpacity = 0.7f
        ),
        BrushPreset(
            id = "hatching", group = "Textures", name = "Hatching", size = 40f, hardness = 0.95f, spacing = 0.35f,
            smoothing = 0.3f, shapeId = "yume-hatch", rotation = 45f, pressureSize = 0.4f, pressureOpacity = 0.4f
        ),
        BrushPreset(
            id = "noise", group = "Textures", name = "Grit", size = 60f, hardness = 0.6f, flow = 0.7f,
            spacing = 0.15f, smoothing = 0.2f, grain = 0.9f, jitter = 0.2f, rotationMode = 3,
            pressureSize = 0.4f, pressureOpacity = 0.5f
        ),
        // ---------------------------------------------------- elements
        BrushPreset(
            id = "foliage", group = "Elements", name = "Foliage", size = 70f, hardness = 0.9f, spacing = 0.45f,
            smoothing = 0.1f, jitter = 0.35f, shapeId = "yume-foliage", rotationMode = 3,
            pressureSize = 0.6f, pressureOpacity = 0.3f
        ),
        BrushPreset(
            id = "stars", group = "Elements", name = "Stars", size = 30f, hardness = 0.95f, spacing = 1.4f,
            smoothing = 0.1f, jitter = 0.9f, shapeId = "yume-star", rotationMode = 3,
            pressureSize = 0.8f, pressureOpacity = 0.4f
        ),
        BrushPreset(
            id = "clouds", group = "Elements", name = "Clouds", size = 160f, hardness = 0f, flow = 0.15f,
            spacing = 0.12f, smoothing = 0.2f, grain = 0.4f, grainId = "yume-sponge", jitter = 0.3f,
            pressureSize = 0.5f, pressureOpacity = 0.6f
        ),
        BrushPreset(
            id = "rain", group = "Elements", name = "Rain", size = 30f, hardness = 0.9f, opacity = 0.7f,
            spacing = 1.2f, smoothing = 0.1f, jitter = 0.9f, shapeId = "yume-flat", rotation = 80f,
            pressureSize = 0.5f, pressureOpacity = 0.4f
        ),
        // ---------------------------------------------------- erasers and blenders
        BrushPreset(
            id = "eraser-textured", group = "Erasers", name = "Textured Eraser", category = BrushCategory.Eraser,
            size = 40f, hardness = 0.5f, flow = 0.7f, spacing = 0.08f, smoothing = 0.3f, grain = 0.6f,
            grainId = "yume-paper", pressureSize = 0.6f, pressureOpacity = 0.5f
        ),
        BrushPreset(
            id = "eraser-pencil", group = "Erasers", name = "Pencil Eraser", category = BrushCategory.Eraser,
            size = 10f, hardness = 0.9f, flow = 1f, spacing = 0.05f, smoothing = 0.4f,
            pressureSize = 0.4f, pressureOpacity = 0.3f
        ),
        BrushPreset(
            id = "blend-soft", group = "Blending", name = "Soft Blender", category = BrushCategory.Smudge,
            size = 80f, hardness = 0.2f, flow = 0.6f, spacing = 0.03f, smoothing = 0.5f, smudgeAmount = 0.5f,
            pressureSize = 0.5f, pressureOpacity = 0.5f
        ),
        BrushPreset(
            id = "blend-textured", group = "Blending", name = "Textured Blender", category = BrushCategory.Smudge,
            size = 50f, hardness = 0.5f, flow = 0.6f, spacing = 0.04f, smoothing = 0.4f, grain = 0.5f,
            grainId = "yume-canvas", smudgeAmount = 0.4f, pressureSize = 0.5f, pressureOpacity = 0.5f
        ),
        BrushPreset(
            id = "blend-finger", group = "Blending", name = "Finger", category = BrushCategory.Smudge,
            size = 35f, hardness = 0.4f, flow = 0.7f, spacing = 0.03f, smoothing = 0.5f, smudgeAmount = 0.7f,
            pressureSize = 0.6f, pressureOpacity = 0.6f
        )
    )

    fun preset(id: String): BrushPreset = defaults.firstOrNull { it.id == id } ?: defaults.first()

    fun byCategory(category: BrushCategory): List<BrushPreset> =
        defaults.filter { it.category == category }

    /** Library groups in display order. */
    val groupOrder = listOf(
        "Sketching", "Inking", "Calligraphy", "Painting", "Airbrushing", "Textures", "Elements",
        "Erasers", "Blending", "Imported", "Custom"
    )

    fun defaultFor(category: BrushCategory): BrushPreset =
        byCategory(category).first()
}
