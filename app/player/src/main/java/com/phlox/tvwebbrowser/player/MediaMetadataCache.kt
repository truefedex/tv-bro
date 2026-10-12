package com.phlox.tvwebbrowser.player

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Session-scoped, bounded cache of manifest metadata keyed by stream URL. Populated
 * in the background by [MediaMetadataPrefetcher] (and opportunistically by playback),
 * so the stream list can render rows immediately and fill metadata in as it arrives.
 * Listener callbacks are always delivered on the main thread.
 */
object MediaMetadataCache {
    fun interface Listener {
        fun onMetadataUpdated(url: String)
    }

    private const val MAX_ENTRIES = 256
    private val lock = Any()
    private val entries = LinkedHashMap<String, MediaMetadata>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun get(url: String): MediaMetadata? = synchronized(lock) { entries[url] }

    fun put(url: String, metadata: MediaMetadata) {
        synchronized(lock) {
            entries.remove(url)
            entries[url] = metadata
            while (entries.size > MAX_ENTRIES) {
                val oldest = entries.keys.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
        mainHandler.post { listeners.forEach { runCatching { it.onMetadataUpdated(url) } } }
    }
}
