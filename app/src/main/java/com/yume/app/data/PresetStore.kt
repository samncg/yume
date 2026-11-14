package com.yume.app.data

import android.content.Context
import com.yume.app.engine.BrushLibrary
import com.yume.app.engine.BrushPreset
import org.json.JSONArray

/**
 * Persists the brush library: built-in presets keep their id (so a reset can
 * restore the factory settings) and user-created presets are stored alongside
 * them in the same category list.
 */
class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("yume_presets", Context.MODE_PRIVATE)

    fun load(): List<BrushPreset> {
        val raw = prefs.getString(KEY, null) ?: return BrushLibrary.defaults
        return runCatching {
            val array = JSONArray(raw)
            val out = ArrayList<BrushPreset>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                val base = BrushLibrary.defaults.firstOrNull { it.id == id }
                    ?: BrushLibrary.defaults.first()
                out.add(BrushPreset.fromJson(o, base))
            }
            if (out.isEmpty()) return@runCatching BrushLibrary.defaults
            // Built-ins added by a newer version join the saved library.
            val have = out.mapTo(HashSet()) { it.id }
            val added = BrushLibrary.defaults.filter { it.id !in have }
            out + added
        }.getOrDefault(BrushLibrary.defaults)
    }

    fun save(presets: List<BrushPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "presets_v1"
    }
}
