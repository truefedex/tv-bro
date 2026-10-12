package com.phlox.tvwebbrowser.player

import java.net.URI

/** Bounded text inspection, not XML execution and not a rewrite of the site's response. */
object ManifestInspector {
    data class Info(val children: Set<String>, val protectedContent: Boolean, val metadata: MediaMetadata = MediaMetadata())
    fun inspect(url: String, text: String, kind: MediaRequestCatalog.Kind): Info {
        if (kind == MediaRequestCatalog.Kind.DASH) {
            return Info(emptySet(),
                Regex("<(?:[\\w-]+:)?ContentProtection\\b", RegexOption.IGNORE_CASE).containsMatchIn(text),
                dashMetadata(text))
        }
        if (kind != MediaRequestCatalog.Kind.HLS || !text.trimStart().startsWith("#EXTM3U")) return Info(emptySet(), false)
        val children = mutableSetOf<String>()
        val languages = linkedSetOf<String>()
        var resolution: Pair<Int, Int>? = null
        var bandwidth: Long? = null
        var variantFollows = false
        var protectedContent = false
        text.lineSequence().map(String::trim).forEach { line ->
            if (variantFollows && line.isNotEmpty() && !line.startsWith('#')) {
                runCatching { URI(url).resolve(line).toString() }.getOrNull()?.let(children::add)
                variantFollows = false
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                variantFollows = true
                val attributes = attributes(line.substringAfter(':'))
                attributes["RESOLUTION"]?.let { value ->
                    val parts = value.split('x', 'X', '*')
                    val width = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val height = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (width != null && height != null) {
                        val current = resolution
                        if (current == null || width.toLong() * height > current.first.toLong() * current.second) {
                            resolution = width to height
                        }
                    }
                }
                attributes["BANDWIDTH"]?.toLongOrNull()?.let { value ->
                    val current = bandwidth
                    if (current == null || value > current) bandwidth = value
                }
            }
            if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                val attributes = attributes(line.substringAfter(':'))
                if (line.startsWith("#EXT-X-MEDIA:") &&
                    (attributes["TYPE"].equals("AUDIO", true) || attributes["TYPE"].equals("SUBTITLES", true))) {
                    attributes["LANGUAGE"]?.takeIf { it.isNotBlank() }?.let(languages::add)
                }
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
        val metadata = MediaMetadata(resolution?.first, resolution?.second, languages.toList(), null, bandwidth)
        return Info(children.filter(MediaRequestCatalog::isHttp).toSet(), protectedContent, metadata)
    }

    private fun attributes(line: String): Map<String, String> {
        val result = HashMap<String, String>()
        Regex("([A-Za-z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line).forEach {
            result[it.groupValues[1].uppercase()] = it.groupValues[2].trim('"')
        }
        return result
    }

    private fun dashMetadata(text: String): MediaMetadata {
        var width: Int? = null
        var height: Int? = null
        Regex("\\bwidth=\"(\\d+)\"").findAll(text).forEach { match ->
            match.groupValues[1].toIntOrNull()?.let {
                val current = width
                if (current == null || it > current) width = it
            }
        }
        Regex("\\bheight=\"(\\d+)\"").findAll(text).forEach { match ->
            match.groupValues[1].toIntOrNull()?.let {
                val current = height
                if (current == null || it > current) height = it
            }
        }
        val languages = Regex("\\blang=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.distinct().toList()
        val durationMs = Regex("mediaPresentationDuration=\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.let(::parseIsoDurationMs)
        val bandwidth = Regex("\\bbandwidth=\"(\\d+)\"").findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.maxOrNull()
        return MediaMetadata(width, height, languages, durationMs, bandwidth)
    }

    private fun parseIsoDurationMs(value: String): Long? {
        val match = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$").find(value.trim())
            ?: return null
        val days = match.groupValues[1].toLongOrNull() ?: 0
        val hours = match.groupValues[2].toLongOrNull() ?: 0
        val minutes = match.groupValues[3].toLongOrNull() ?: 0
        val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
        val ms = (((days * 24 + hours) * 60 + minutes) * 60000L) + (seconds * 1000).toLong()
        return ms.takeIf { it > 0 }
    }
}
