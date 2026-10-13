package com.phlox.tvwebbrowser.player

import java.net.URI
import java.util.Locale

/**
 * Canonical identity for a media URL, so repeated observations of the same resource collapse
 * into one entry. Signed/tokenised gateways name the same stream every time while their
 * credentials rotate (e.g. ".../secure.php?path=ab12/index.m3u8&e=...&sig=..."), so parameters
 * are only dropped when at least one of them names a resource (contains a path separator or a
 * manifest extension). If no parameter names a resource the whole query is kept verbatim, so
 * distinct variants (e.g. "...?quality=low" vs "...?quality=high") are never merged.
 */
object MediaUrlIdentity {
    fun key(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.substringBefore('#')
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val base = "$host${uri.path.orEmpty().lowercase(Locale.ROOT)}"
        val query = uri.rawQuery ?: return base
        val resourceParams = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = part.substring(0, eq)
            val value = decode(part.substring(eq + 1))
            if (namesResource(value)) "$name=${value.lowercase(Locale.ROOT)}" else null
        }.sorted()
        return if (resourceParams.isEmpty()) "$base?$query"
        else "$base?${resourceParams.joinToString("&")}"
    }

    private fun namesResource(value: String): Boolean {
        val v = value.lowercase(Locale.ROOT)
        return v.contains('/') || v.contains(".m3u8") || v.contains(".mpd")
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
