package com.phlox.tvwebbrowser.browser.adblock

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What the app knows of a list: its updates and what the compiler found in it */
class ListMeta(
    /** When the list was last downloaded or found unchanged; 0: never */
    var lastSuccess: Long = 0,
    /** When the last download failed (with [failures] in a row) */
    var lastAttempt: Long = 0,
    var failures: Int = 0,
    var etag: String? = null,
    var lastModified: String? = null,
    /** How often the list asks to be updated (its `! Expires:`) */
    var expiresHours: Int? = null,
    /** The request filters the engine has of it, and the filters it skipped */
    var filters: Int = 0,
    var skipped: Int = 0,
)

/**
 * The lists the user turned on, the address of their own list, and the [ListMeta] of each list, kept in a
 * JSON file written atomically. Not thread-safe: [AdBlockService] uses it under its lock.
 */
class FilterListsStore(private val file: File, private val language: String) {
    val enabled = LinkedHashSet<String>()
    var customUrl = ""
    private val metas = HashMap<String, ListMeta>()

    fun meta(id: String): ListMeta = metas.getOrPut(id) { ListMeta() }

    fun forget(id: String) {
        metas.remove(id)
    }

    /** The lists to compile, in a fixed order: the catalog ones turned on, then the user's own one */
    fun enabledSources(): List<FilterListInfo> {
        val sources = FilterListCatalog.lists.filter { it.id in enabled }.toMutableList()
        if (customUrl.isNotEmpty()) sources += FilterListInfo(FilterListCatalog.CUSTOM, "", customUrl)
        return sources
    }

    fun load() {
        enabled.clear()
        metas.clear()
        customUrl = ""
        val json = try {
            if (file.exists()) JSONObject(file.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Can not read $file", e)
            null
        }
        if (json == null) {
            enabled += FilterListCatalog.defaults(language)
            return
        }
        try {
            val ids = json.getJSONArray("enabled")
            for (i in 0 until ids.length()) enabled += ids.getString(i)
            customUrl = json.optString("customUrl")
            val lists = json.optJSONObject("lists") ?: JSONObject()
            for (id in lists.keys()) {
                val m = lists.getJSONObject(id)
                metas[id] = ListMeta(
                    lastSuccess = m.optLong("lastSuccess"), lastAttempt = m.optLong("lastAttempt"),
                    failures = m.optInt("failures"), etag = m.optString("etag").ifEmpty { null },
                    lastModified = m.optString("lastModified").ifEmpty { null },
                    expiresHours = if (m.has("expiresHours")) m.getInt("expiresHours") else null,
                    filters = m.optInt("filters"), skipped = m.optInt("skipped"),
                )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Bad $file", e)
            enabled.clear()
            enabled += FilterListCatalog.defaults(language)
        }
    }

    fun save() {
        val lists = JSONObject()
        for ((id, m) in metas) {
            lists.put(id, JSONObject()
                .put("lastSuccess", m.lastSuccess).put("lastAttempt", m.lastAttempt).put("failures", m.failures)
                .put("etag", m.etag ?: "").put("lastModified", m.lastModified ?: "")
                .put("filters", m.filters).put("skipped", m.skipped)
                .apply { m.expiresHours?.let { put("expiresHours", it) } })
        }
        val json = JSONObject().put("enabled", JSONArray(enabled.toList())).put("customUrl", customUrl).put("lists", lists)
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use {
                it.write(json.toString().toByteArray())
                it.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("can not rename $temp")
        } catch (e: IOException) {
            Log.e(TAG, "Can not write $file", e)
        }
    }

    private companion object {
        const val TAG = "FilterListsStore"
    }
}
