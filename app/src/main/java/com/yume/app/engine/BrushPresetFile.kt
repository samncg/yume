package com.yume.app.engine

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The `.yumebrush` file: one or more presets as JSON, with the stamp shapes and
 * grain textures they use embedded as base64 PNG, so a brush travels whole.
 */
object BrushPresetFile {
    private const val FORMAT = "yume-brush"

    fun export(presets: List<BrushPreset>): String {
        val assets = JSONObject()
        val list = JSONArray()
        for (p in presets) {
            list.put(p.toJson())
            BrushAssets.bytes("shapes", p.shapeId)?.let { assets.put("shape:${p.shapeId}", Base64.encodeToString(it, Base64.NO_WRAP)) }
            BrushAssets.bytes("grains", p.grainId)?.let { assets.put("grain:${p.grainId}", Base64.encodeToString(it, Base64.NO_WRAP)) }
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", 1)
            put("presets", list)
            put("assets", assets)
        }.toString(2)
    }

    /**
     * Reads a preset file. Every preset gets a fresh id (so importing twice, or
     * importing a built-in's export, never collides) and becomes a user preset.
     * Returns an empty list for a file that is not a Yume brush.
     */
    fun import(text: String): List<BrushPreset> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (root.optString("format") != FORMAT) return emptyList()
        val assets = root.optJSONObject("assets")
        if (assets != null) {
            for (key in assets.keys()) {
                val bytes = runCatching { Base64.decode(assets.getString(key), Base64.DEFAULT) }.getOrNull() ?: continue
                when {
                    key.startsWith("shape:") -> BrushAssets.put("shapes", key.removePrefix("shape:"), bytes)
                    key.startsWith("grain:") -> BrushAssets.put("grains", key.removePrefix("grain:"), bytes)
                }
            }
        }
        val arr = root.optJSONArray("presets") ?: return emptyList()
        val out = ArrayList<BrushPreset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val base = BrushLibrary.defaults.first()
            val p = BrushPreset.fromJson(o, base)
            out.add(
                p.copy(
                    id = "imported-" + UUID.randomUUID().toString().substring(0, 8),
                    builtIn = false,
                    // Asset ids pass through the same sanitiser as the files.
                    shapeId = p.shapeId.filter { it.isLetterOrDigit() || it == '-' }.take(40),
                    grainId = p.grainId.filter { it.isLetterOrDigit() || it == '-' }.take(40),
                    group = if (p.group.isEmpty() || p.group == "Custom") "Imported" else p.group
                )
            )
        }
        return out
    }
}
