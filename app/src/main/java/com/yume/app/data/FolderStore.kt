package com.yume.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A gallery folder: a named group of artworks. */
data class GalleryFolder(val id: String, val name: String, val createdAt: Long)

/**
 * Gallery folders and the order you arranged things in. Like pins, these are
 * gallery preferences and not part of any document: the folders, which
 * artwork sits in which, and the dragged order of each screen are kept here,
 * apart from the projects. One level deep; an artwork is in one folder or
 * none. Deleting a folder never deletes artworks: they move back out.
 *
 * Order keys: a project's id, or [folderKey] of a folder. A screen's order is
 * empty until something on it is dragged; see [arrange].
 *
 * Thread-safe; [version] goes up on every change.
 */
class FolderStore(context: Context, prefsName: String = "gallery_folders") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val lock = Any()
    private val folders = LinkedHashMap<String, GalleryFolder>()
    /** Project id -> folder id. */
    private val member = HashMap<String, String>()
    /** Screen ("" the top of the gallery, else a folder id) -> arranged keys. */
    private val orders = HashMap<String, List<String>>()

    @Volatile var version: Int = 0
        private set

    init {
        runCatching {
            val o = JSONObject(prefs.getString("data", null) ?: return@runCatching)
            val a = o.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                val id = f.optString("id")
                if (id.isEmpty()) continue
                folders[id] = GalleryFolder(id, f.optString("name", "Folder"), f.optLong("created", 0L))
            }
            val m = o.optJSONObject("member") ?: JSONObject()
            for (k in m.keys()) {
                val fid = m.optString(k)
                if (fid in folders) member[k] = fid
            }
            val ord = o.optJSONObject("order") ?: JSONObject()
            for (k in ord.keys()) {
                if (k.isNotEmpty() && k !in folders) continue
                val arr = ord.optJSONArray(k) ?: continue
                orders[k] = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            }
        }
    }

    private fun persist() {
        val o = JSONObject()
        val a = JSONArray()
        for (f in folders.values) a.put(JSONObject().put("id", f.id).put("name", f.name).put("created", f.createdAt))
        o.put("folders", a)
        val m = JSONObject()
        for ((k, v) in member) m.put(k, v)
        o.put("member", m)
        val ord = JSONObject()
        for ((k, v) in orders) ord.put(k, JSONArray(v))
        o.put("order", ord)
        prefs.edit().putString("data", o.toString()).apply()
        version++
    }

    /** Folders, newest first. */
    fun folders(): List<GalleryFolder> = synchronized(lock) { folders.values.sortedByDescending { it.createdAt } }

    fun folder(id: String?): GalleryFolder? = synchronized(lock) { id?.let { folders[it] } }

    /** The folder [projectId] is in, or null. */
    fun folderOf(projectId: String): String? = synchronized(lock) { member[projectId] }

    fun create(name: String): GalleryFolder = synchronized(lock) {
        val f = GalleryFolder(UUID.randomUUID().toString(), cleanName(name), System.currentTimeMillis())
        folders[f.id] = f
        persist()
        f
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        val f = folders[id] ?: return@synchronized
        folders[id] = f.copy(name = cleanName(name))
        persist()
    }

    /** Removes the folder; its artworks move back to the top of the gallery. */
    fun delete(id: String) = synchronized(lock) {
        if (folders.remove(id) == null) return@synchronized
        member.values.removeAll { it == id }
        orders.remove(id)
        orders[""]?.let { top -> orders[""] = top - folderKey(id) }
        persist()
    }

    /** Puts [projectId] in [folderId] (null: out of any folder). */
    fun move(projectId: String, folderId: String?) = synchronized(lock) {
        if (folderId != null && folderId !in folders) return@synchronized
        if (member[projectId] == folderId) return@synchronized
        if (folderId == null) member.remove(projectId) else member[projectId] = folderId
        persist()
    }

    /** The arranged order of a screen (null: the top of the gallery); empty if never arranged. */
    fun order(folderId: String?): List<String> = synchronized(lock) { orders[folderId ?: ""].orEmpty() }

    fun setOrder(folderId: String?, keys: List<String>) = synchronized(lock) {
        if (folderId != null && folderId !in folders) return@synchronized
        orders[folderId ?: ""] = keys.distinct()
        persist()
    }

    /**
     * A project dropped on another, on the top screen: a new folder holding
     * both takes the target's place ([topOrder] is that screen as shown).
     */
    fun group(targetId: String, droppedId: String, topOrder: List<String>): GalleryFolder = synchronized(lock) {
        var name = "Folder"
        var n = 2
        while (folders.values.any { it.name == name }) name = "Folder ${n++}"
        val f = GalleryFolder(UUID.randomUUID().toString(), name, System.currentTimeMillis())
        folders[f.id] = f
        member[targetId] = f.id
        member[droppedId] = f.id
        orders[f.id] = listOf(targetId, droppedId)
        orders[""] = topOrder.map { if (it == targetId) folderKey(f.id) else it }.filter { it != droppedId }.distinct()
        persist()
        f
    }

    /** A project dropped on a folder: it goes in, last. [fromOrder] is the screen it left, as shown. */
    fun drop(projectId: String, folderId: String, fromOrder: List<String>) = synchronized(lock) {
        if (folderId !in folders) return@synchronized
        val from = member[projectId] ?: ""
        member[projectId] = folderId
        // A screen never arranged stays that way (most recent first).
        if (orders[from] != null) orders[from] = fromOrder - projectId
        orders[folderId]?.let { orders[folderId] = (it - projectId) + projectId }
        persist()
    }

    /**
     * A project dragged out of its folder. Not in the top screen's order, it
     * shows there as new: near the top, after the pinned artworks.
     */
    fun takeOut(projectId: String) = synchronized(lock) {
        val from = member.remove(projectId) ?: return@synchronized
        orders[from]?.let { orders[from] = it - projectId }
        orders[""]?.let { orders[""] = it - projectId }
        persist()
    }

    private fun cleanName(name: String) = name.trim().take(60).ifBlank { "Folder" }

    companion object {
        fun folderKey(id: String) = "f:$id"
        fun isFolderKey(key: String) = key.startsWith("f:")
        fun folderIdOf(key: String) = key.removePrefix("f:")

        /**
         * What a screen shows, in order. Never arranged: [defaultOrder] (folders,
         * then pinned, then most recent). Arranged: the saved order, with
         * anything new since (a new artwork, an import) after the pinned ones
         * that lead it, so new work still shows near the top.
         */
        fun arrange(defaultOrder: List<String>, saved: List<String>, pinned: (String) -> Boolean): List<String> {
            if (saved.isEmpty()) return defaultOrder
            val present = defaultOrder.toHashSet()
            val known = saved.filter { it in present }.distinct()
            val knownSet = known.toHashSet()
            val fresh = defaultOrder.filter { it !in knownSet }
            val lead = known.takeWhile { !isFolderKey(it) && pinned(it) }
            return lead + fresh + known.drop(lead.size)
        }

        /** [list] with [key] moved to [to] (the index it ends up at). */
        fun moved(list: List<String>, key: String, to: Int): List<String> {
            val from = list.indexOf(key)
            if (from < 0 || from == to) return list
            val out = list.toMutableList()
            out.removeAt(from)
            out.add(to.coerceIn(0, out.size), key)
            return out
        }
    }
}
