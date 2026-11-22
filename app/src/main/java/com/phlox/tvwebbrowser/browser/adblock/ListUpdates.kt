package com.phlox.tvwebbrowser.browser.adblock

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * When the downloaded lists of the app (the filter lists of ad blocking, the lists of sites of the site filter) are
 * downloaded again, and the checks of a downloaded file, shared by their services
 */
class ListUpdates(
    /** The update interval of a list that asks for none, and its bounds */
    private val defaultIntervalHours: Int = 4 * 24,
    private val minIntervalHours: Int = 24,
    private val maxIntervalHours: Int = 7 * 24,
    /** After the first, second and further failures in a row */
    private val retryDelaysMs: LongArray = longArrayOf(HOUR_MS, 6 * HOUR_MS, 24 * HOUR_MS),
    /** Of a list that was never downloaded (nothing works without it); [retryDelaysMs] if null */
    private val missingRetryDelaysMs: LongArray? = null,
) {
    /** Whether the list should be downloaded now: its time came (or it is missing), and not too soon after a failure */
    fun isDue(meta: ListMeta, exists: Boolean, now: Long): Boolean {
        val delays = if (!exists && missingRetryDelaysMs != null) missingRetryDelaysMs else retryDelaysMs
        if (meta.failures > 0 && now - meta.lastAttempt in 0 until delays[minOf(meta.failures, delays.size) - 1]) {
            return false
        }
        if (!exists) return true
        val hours = (meta.expiresHours ?: defaultIntervalHours).coerceIn(minIntervalHours, maxIntervalHours)
        return now - meta.lastSuccess >= hours * HOUR_MS
    }

    companion object {
        const val HOUR_MS = 60 * 60 * 1000L

        /** A downloaded file must look like a list, not like the error page of a portal or a server */
        @Throws(IOException::class)
        fun checkList(file: File) {
            if (file.length() == 0L) throw IOException("empty list")
            val start = file.inputStream().use { input ->
                val bytes = ByteArray(512)
                val read = input.read(bytes)
                if (read <= 0) "" else String(bytes, 0, read)
            }
            if (start.trimStart().startsWith("<")) throw IOException("not a filter list (HTML)")
        }

        @Throws(IOException::class)
        fun replace(temp: File, file: File) {
            if (temp.renameTo(file)) return
            file.delete()
            if (!temp.renameTo(file)) throw IOException("can not rename $temp")
        }

        fun toJson(m: ListMeta): JSONObject = JSONObject()
            .put("lastSuccess", m.lastSuccess).put("lastAttempt", m.lastAttempt).put("failures", m.failures)
            .put("etag", m.etag ?: "").put("lastModified", m.lastModified ?: "")
            .put("filters", m.filters).put("skipped", m.skipped)
            .apply { m.expiresHours?.let { put("expiresHours", it) } }

        fun metaOf(m: JSONObject) = ListMeta(
            lastSuccess = m.optLong("lastSuccess"), lastAttempt = m.optLong("lastAttempt"),
            failures = m.optInt("failures"), etag = m.optString("etag").ifEmpty { null },
            lastModified = m.optString("lastModified").ifEmpty { null },
            expiresHours = if (m.has("expiresHours")) m.getInt("expiresHours") else null,
            filters = m.optInt("filters"), skipped = m.optInt("skipped"),
        )
    }
}
