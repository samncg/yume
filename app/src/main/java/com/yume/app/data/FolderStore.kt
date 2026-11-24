package com.yume.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A gallery folder: a named group of artworks. */
data class GalleryFolder(val id: String, val name: String, val createdAt: Long)

/**
 * Gallery folders. Like pins, a folder is a gallery preference and not part
 * of any document: the folders and which artwork sits in which are kept
 * here, apart from the projects. One level deep; an artwork is in one folder
 * or none. Deleting a folder never deletes artworks: they move back out.
 *
 * Thread-safe; [version] goes up on every change.
 */
class FolderStore(context: Context, prefsName: String = "gallery_folders") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val lock = Any()
    private val folders = LinkedHashMap<String, GalleryFolder>()
    /** Project id -> folder id. */
    private val member = HashMap<String, String>()

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
        persist()
    }

    /** Puts [projectId] in [folderId] (null: out of any folder). */
    fun move(projectId: String, folderId: String?) = synchronized(lock) {
        if (folderId != null && folderId !in folders) return@synchronized
        if (member[projectId] == folderId) return@synchronized
        if (folderId == null) member.remove(projectId) else member[projectId] = folderId
        persist()
    }

    private fun cleanName(name: String) = name.trim().take(60).ifBlank { "Folder" }
}
