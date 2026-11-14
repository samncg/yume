package com.yume.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Favourite brushes, recently used brushes and the open Brush Studio group. */
object BrushPrefs {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("yume_brush_prefs", Context.MODE_PRIVATE)
        prefs = p
        favorites = p.getString("favorites", "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        recents = p.getString("recents", "").orEmpty().split(',').filter { it.isNotEmpty() }
        group = p.getString("group", group) ?: group
    }

    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recents by mutableStateOf<List<String>>(emptyList())
        private set

    /** Selected Brush Studio group: a library group, [FAVORITES], [RECENT] or [ALL]. */
    var group by mutableStateOf(ALL)
        private set

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs?.edit()?.putString("favorites", favorites.joinToString(","))?.apply()
    }

    fun noteUsed(id: String) {
        recents = (listOf(id) + recents.filter { it != id }).take(12)
        prefs?.edit()?.putString("recents", recents.joinToString(","))?.apply()
    }

    fun chooseGroup(g: String) {
        group = g
        prefs?.edit()?.putString("group", g)?.apply()
    }

    const val FAVORITES = "★ Favorites"
    const val RECENT = "Recent"
    const val ALL = "All"
}
