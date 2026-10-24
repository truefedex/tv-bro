package com.phlox.tvwebbrowser.adblock

/**
 * Types of requests as bits of a mask. A filter has the mask of the types it applies to; a request has its
 * type, or the types it may be when the engine does not tell (WebView).
 */
object ResourceType {
    const val OTHER = 1
    const val SCRIPT = 1 shl 1
    const val IMAGE = 1 shl 2
    const val STYLESHEET = 1 shl 3
    const val OBJECT = 1 shl 4
    /** A frame inside a page */
    const val SUBDOCUMENT = 1 shl 5
    const val XHR = 1 shl 6
    const val PING = 1 shl 7
    const val MEDIA = 1 shl 8
    const val FONT = 1 shl 9
    const val WEBSOCKET = 1 shl 10
    /** The page itself (main frame); filters apply to it only with the `document` option */
    const val DOCUMENT = 1 shl 11

    /** The types of filters without type options: all except the page itself (as in Adblock Plus) */
    const val DEFAULT = (1 shl 11) - 1
    const val ALL = (1 shl 12) - 1

    /** The type of a request of the WebExtensions API (`webRequest.ResourceType`, as GeckoView gives it) */
    fun fromWebExtension(type: String?): Int = when (type) {
        "main_frame" -> DOCUMENT
        "sub_frame" -> SUBDOCUMENT
        "stylesheet" -> STYLESHEET
        "script" -> SCRIPT
        "image", "imageset" -> IMAGE
        "font" -> FONT
        "object", "object_subrequest" -> OBJECT
        "xmlhttprequest" -> XHR
        "ping", "beacon", "csp_report" -> PING
        "media" -> MEDIA
        "websocket" -> WEBSOCKET
        else -> OTHER
    }

    /**
     * The types a request may be when the engine does not tell (WebView): from its Accept header and the
     * extension of its path. A filter applies when one of its types is among them, an exception too, so a
     * guess that is wider than the truth blocks less than one wrong single type. `*` / `*` (scripts, fetch,
     * fonts, beacons…) without a known extension is a script, an XHR or other: never a ping, or
     * `*$ping,third-party` would block every third-party script.
     */
    fun guessFromAccept(accept: String?, url: String, isMainFrame: Boolean): Int {
        if (isMainFrame) return DOCUMENT
        val a = accept?.lowercase() ?: ""
        when {
            a.startsWith("text/html") || a.contains("application/xhtml") -> return SUBDOCUMENT
            a.startsWith("image/") || a.contains("image/webp") || a.contains("image/avif") -> return IMAGE
            a.startsWith("text/css") -> return STYLESHEET
            a.startsWith("video/") || a.startsWith("audio/") -> return MEDIA
            a.startsWith("application/json") -> return XHR
        }
        return when (extensionOf(url)) {
            "js", "mjs" -> SCRIPT
            "css" -> STYLESHEET
            "woff", "woff2", "ttf", "otf", "eot" -> FONT
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp" -> IMAGE
            "mp4", "webm", "m3u8", "mpd", "m4s", "ts", "mp3", "aac", "ogg" -> MEDIA
            "json" -> XHR
            "html", "htm" -> SUBDOCUMENT or XHR
            else -> SCRIPT or XHR or OTHER
        }
    }

    /** The extension of the last segment of the path of the [url], lowercase; "" if none */
    private fun extensionOf(url: String): String {
        var end = url.length
        val query = url.indexOf('?')
        if (query >= 0) end = query
        val fragment = url.indexOf('#')
        if (fragment in 0 until end) end = fragment
        val slash = url.lastIndexOf('/', end - 1)
        val dot = url.lastIndexOf('.', end - 1)
        if (dot <= slash || end - dot > 6) return ""
        return url.substring(dot + 1, end).lowercase()
    }

    /** The type options of filters (and the aliases of uBlock Origin) */
    val byOption: Map<String, Int> = mapOf(
        "other" to OTHER,
        "script" to SCRIPT,
        "image" to IMAGE,
        "stylesheet" to STYLESHEET,
        "css" to STYLESHEET,
        "object" to OBJECT,
        "subdocument" to SUBDOCUMENT,
        "frame" to SUBDOCUMENT,
        "xmlhttprequest" to XHR,
        "xhr" to XHR,
        "ping" to PING,
        "beacon" to PING,
        "media" to MEDIA,
        "font" to FONT,
        "websocket" to WEBSOCKET,
        "document" to DOCUMENT,
        "doc" to DOCUMENT,
    )
}
