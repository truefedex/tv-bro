package com.phlox.tvwebbrowser.player

import java.net.URI

/** Bounded text inspection, not XML execution and not a rewrite of the site's response. */
object ManifestInspector {
    data class Info(val children: Set<String>, val protectedContent: Boolean)
    fun inspect(url: String, text: String, kind: MediaRequestCatalog.Kind): Info {
        if (kind == MediaRequestCatalog.Kind.DASH) {
            return Info(emptySet(), Regex("<(?:[\\w-]+:)?ContentProtection\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
        if (kind != MediaRequestCatalog.Kind.HLS || !text.trimStart().startsWith("#EXTM3U")) return Info(emptySet(), false)
        val children = mutableSetOf<String>()
        var variantFollows = false
        var protectedContent = false
        text.lineSequence().map(String::trim).forEach { line ->
            if (variantFollows && line.isNotEmpty() && !line.startsWith('#')) {
                runCatching { URI(url).resolve(line).toString() }.getOrNull()?.let(children::add)
                variantFollows = false
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) variantFollows = true
            if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                Regex("(?:^|,)URI=\"([^\"]+)\"").find(line.substringAfter(':'))?.groupValues?.get(1)?.let { child ->
                    runCatching { URI(url).resolve(child).toString() }.getOrNull()?.let(children::add)
                }
            }
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                val method = Regex("(?:^|,)METHOD=([^,]+)").find(line.substringAfter(':'))?.groupValues?.get(1)
                if (method != null && method !in setOf("NONE", "AES-128")) protectedContent = true
                val format = Regex("KEYFORMAT=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                if (format != null && format != "identity") protectedContent = true
            }
        }
        return Info(children.filter(MediaRequestCatalog::isHttp).toSet(), protectedContent)
    }
}
