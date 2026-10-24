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
