package com.phlox.tvwebbrowser.webengine.webview

/**
 * The frames that can read the blobs of an origin ([WebViewBlobDownloads]): each frame says hello when its document
 * starts, and a blob: address can be read only by a frame of its origin. The main frames of the origin are asked
 * first, the newest first. The hello of a new document can come before its page starts, so frames are never
 * cleared: a newer frame of the origin goes before older ones, and at most [MAX] are kept.
 */
class BlobFrames<T>(private val max: Int = MAX) {
    companion object {
        const val MAX = 32

        /** The origin of a blob: address (`blob:https://site.com/uuid` → `https://site.com`), "null" for an opaque one */
        fun originOf(blobUrl: String): String? {
            if (!blobUrl.startsWith("blob:", ignoreCase = true)) return null
            val inner = blobUrl.substring(5)
            val schemeEnd = inner.indexOf("://")
            if (schemeEnd <= 0) return "null"
            val hostEnd = inner.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd + 3).let { if (it < 0) inner.length else it }
            return inner.substring(0, hostEnd).lowercase()
        }
    }

    private class Entry<T>(val origin: String, val mainFrame: Boolean, val frame: T)

    /** The newest last */
    private val entries = ArrayList<Entry<T>>()

    fun onHello(origin: String, mainFrame: Boolean, frame: T) {
        val key = origin.lowercase()
        entries.removeAll { it.frame == frame }
        entries.add(Entry(key, mainFrame, frame))
        if (entries.size > max) entries.removeAt(0)
    }

    /** The frames to ask for a blob of the [origin], in order */
    fun candidates(origin: String): List<T> {
        val key = origin.lowercase()
        val ofOrigin = entries.asReversed().filter { it.origin == key }
        return (ofOrigin.filter { it.mainFrame } + ofOrigin.filter { !it.mainFrame }).map { it.frame }
    }
}
