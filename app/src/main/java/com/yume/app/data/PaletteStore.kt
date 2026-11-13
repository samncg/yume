package com.yume.app.data

import android.content.Context
import com.yume.app.engine.EditorUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A named set of colours the user keeps between documents. */
data class Palette(val id: String, val name: String, val colors: List<Int>)

/**
 * App-wide colour palettes, stored as JSON in SharedPreferences (like brush
 * presets). The first launch seeds one "Default" palette.
 */
class PaletteStore(context: Context) {

    private val prefs = context.getSharedPreferences("yume_palettes", Context.MODE_PRIVATE)

    private val _palettes = MutableStateFlow(load())
    val palettes: StateFlow<List<Palette>> = _palettes

    private val _activeId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> _palettes.value.any { it.id == id } }
            ?: _palettes.value.first().id
    )
    val activeId: StateFlow<String> = _activeId

    private fun load(): List<Palette> {
        val raw = prefs.getString(KEY, null)
        val parsed = raw?.let {
            runCatching {
                val array = JSONArray(it)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    val cs = o.optJSONArray("colors") ?: JSONArray()
                    Palette(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "Palette"),
                        colors = (0 until cs.length()).map { cs.getInt(it) }
                    )
                }
            }.getOrNull()
        }
        return parsed?.takeIf { it.isNotEmpty() }
            ?: listOf(Palette(UUID.randomUUID().toString(), "Default", EditorUiState.defaultPalette))
    }

    private fun persist(list: List<Palette>) {
        _palettes.value = list
        val array = JSONArray()
        list.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("colors", JSONArray().apply { p.colors.forEach { put(it) } })
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun select(id: String) {
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Creates an empty palette (optionally starting with [first]) and selects it. */
    fun create(first: Int? = null): Palette {
        val n = _palettes.value.size + 1
        val p = Palette(UUID.randomUUID().toString(), "Palette $n", listOfNotNull(first))
        persist(_palettes.value + p)
        select(p.id)
        return p
    }

    fun rename(id: String, name: String) =
        persist(_palettes.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })

    fun addColor(id: String, color: Int) =
        persist(_palettes.value.map { if (it.id == id && color !in it.colors) it.copy(colors = it.colors + color) else it })

    fun removeColorAt(id: String, index: Int) =
        persist(_palettes.value.map {
            if (it.id == id && index in it.colors.indices) it.copy(colors = it.colors.filterIndexed { i, _ -> i != index }) else it
        })

    /** Deletes a palette; the last one left is never deleted. */
    fun delete(id: String) {
        val rest = _palettes.value.filter { it.id != id }
        if (rest.isEmpty()) return
        persist(rest)
        if (_activeId.value == id) select(rest.first().id)
    }

    private companion object {
        const val KEY = "palettes_v1"
        const val KEY_ACTIVE = "active_palette"
    }
}
