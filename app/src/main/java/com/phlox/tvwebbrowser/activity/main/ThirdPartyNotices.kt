package com.phlox.tvwebbrowser.activity.main

import android.content.res.AssetManager
import org.json.JSONArray

/**
 * The code and resources of other authors the app ships, as About lists them. Each module that adds some
 * keeps their notices in its own `assets/notices/<module>.json` (a file name of its own, so the build merges
 * them): the GeckoView app has those of the gecko module, the WebView app does not, and no code checks the
 * engine. The full texts of the licenses are in `assets/notices/licenses`.
 */
object ThirdPartyNotices {
    const val DIR = "notices"
    const val LICENSES_DIR = "$DIR/licenses"

    /**
     * @param license the name of the license, or what the terms are (trademarks)
     * @param licenseFile the full text of the license in [LICENSES_DIR], null when there is none to ship
     * @param includes the parts used, when the [name] stands for many libraries
     */
    data class Notice(
        val id: String,
        val name: String,
        val license: String,
        val licenseFile: String?,
        val copyright: String?,
        val url: String?,
        val includes: String?,
        val note: String?
    )

    /** The notices of all modules, by name */
    fun load(assets: AssetManager): List<Notice> {
        val files = assets.list(DIR).orEmpty().filter { it.endsWith(".json") }.sorted()
        return files.flatMap { file -> parse(assets.open("$DIR/$file").bufferedReader().use { it.readText() }) }
            .sortedBy { it.name.lowercase() }
    }

    fun parse(json: String): List<Notice> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            fun optional(key: String) = item.optString(key).takeIf { it.isNotEmpty() }
            Notice(
                id = item.getString("id"),
                name = item.getString("name"),
                license = item.getString("license"),
                licenseFile = optional("licenseFile"),
                copyright = optional("copyright"),
                url = optional("url"),
                includes = optional("includes"),
                note = optional("note")
            )
        }
    }

    /** What the notice says, then the text of its license */
    fun text(assets: AssetManager, notice: Notice): String = buildString {
        notice.copyright?.let { appendLine(it) }
        appendLine(notice.license)
        notice.url?.let { appendLine(it) }
        notice.includes?.let { append('\n').appendLine(it) }
        notice.note?.let { append('\n').appendLine(it) }
        notice.licenseFile?.let { file ->
            append('\n')
            append(assets.open("$LICENSES_DIR/$file").bufferedReader().use { it.readText() }.trim())
        }
    }.trim()
}
