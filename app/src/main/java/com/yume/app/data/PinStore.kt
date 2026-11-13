package com.yume.app.data

import android.content.Context

/**
 * Projects pinned to the top of the gallery. Stored apart from the projects
 * themselves (a pin is a gallery preference, not part of the document), as
 * project id -> time it was pinned; the newest pin sits first.
 */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("gallery_pins", Context.MODE_PRIVATE)

    fun pinnedAt(id: String): Long = prefs.getLong(id, 0L)

    fun isPinned(id: String): Boolean = prefs.contains(id)

    fun setPinned(id: String, pinned: Boolean) {
        val edit = prefs.edit()
        if (pinned) edit.putLong(id, System.currentTimeMillis()) else edit.remove(id)
        edit.apply()
    }

    /** Pinned projects first (newest pin first); the rest keep their order. */
    fun <T> order(items: List<T>, idOf: (T) -> String): List<T> {
        val (pinned, rest) = items.partition { isPinned(idOf(it)) }
        return pinned.sortedByDescending { pinnedAt(idOf(it)) } + rest
    }
}
