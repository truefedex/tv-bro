package com.phlox.tvwebbrowser.player

import java.util.Collections
import java.util.concurrent.Executors

/**
 * Best-effort background warm-up of the manifest metadata cache. It never blocks
 * discovery or the UI thread: callers fire it when the stream list opens and rows
 * update later via [MediaMetadataCache]. Only HLS/DASH manifests are worth fetching;
 * progressive files carry no cheaply parseable metadata.
 */
object MediaMetadataPrefetcher {
    private val workers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "TVBroMediaMeta").apply { isDaemon = true }
    }
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun prefetch(url: String, kind: MediaRequestCatalog.Kind, context: MediaRequestContext) {
        if (kind == MediaRequestCatalog.Kind.PROGRESSIVE) return
        if (MediaMetadataCache.get(url) != null) return
        if (!inFlight.add(url)) return
        workers.submit {
            try {
                val (finalUrl, text) = MediaManifestReader.read(url, context)
                val metadata = ManifestInspector.inspect(finalUrl, text, kind).metadata
                if (!metadata.isEmpty()) MediaMetadataCache.put(url, metadata)
            } catch (_: Exception) {
                // Best effort: on failure the row simply keeps showing the URL-derived label.
            } finally {
                inFlight.remove(url)
            }
        }
    }
}
