package com.phlox.tvwebbrowser.webengine.gecko

import android.util.Log
import com.phlox.tvwebbrowser.player.ManifestInspector
import com.phlox.tvwebbrowser.player.MediaManifestReader
import com.phlox.tvwebbrowser.player.MediaMetadataCache
import com.phlox.tvwebbrowser.player.MediaRequestCatalog
import com.phlox.tvwebbrowser.player.MediaRequestContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object NativeMediaResolver {
    data class Resolved(val source: MediaRequestCatalog.Request, val context: MediaRequestContext)
    private val workers = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "TVBroMediaResolve").apply { isDaemon = true } }

    fun resolve(source: String, page: String, privateMode: Boolean, userAgent: String, frame: String? = null,
        result: (Resolved?, String) -> Unit): Future<*> {
        return workers.submit {
            var reason = "unresolved-source"
            val resolved = try {
                val tab = BrowserMediaBridge.activeTab().get(4, TimeUnit.SECONDS)
                    ?: throw IOException("Active media tab unavailable")
                if (tab.privateMode != privateMode || MediaRequestCatalog.documentKey(tab.page) != MediaRequestCatalog.documentKey(page)) {
                    throw IOException("Selected tab changed")
                }
                val tabId = tab.id
                val catalog = MediaRequestCatalog.shared
                var selection = catalog.select(source, page, privateMode, tabId, frame)
                val probes = when (selection) {
                    is MediaRequestCatalog.Selection.Resolved -> listOf(selection.request).filter { it.kind != MediaRequestCatalog.Kind.PROGRESSIVE }
                    is MediaRequestCatalog.Selection.Ambiguous -> selection.candidates
                    else -> emptyList()
                }
                if (probes.size > 8) throw IOException("Ambiguous media sources")
                for (request in probes) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val context = MediaRequestContext(request, catalog.snapshot(page, privateMode, tabId), userAgent)
                    val (finalUrl, text) = MediaManifestReader.read(request.url, context)
                    val info = ManifestInspector.inspect(finalUrl, text, request.kind)
                    if (!info.metadata.isEmpty()) MediaMetadataCache.put(request.url, info.metadata)
                    val aliases = if (finalUrl != request.url) setOf(finalUrl) else emptySet()
                    catalog.annotate(request, info.children + aliases, info.protectedContent)
                }
                selection = catalog.select(source, page, privateMode, tabId, frame)
                when (selection) {
                    is MediaRequestCatalog.Selection.Resolved -> {
                        Log.i("TVBroAutoVideo", "resolved kind=${selection.request.kind} linkedManifests=${selection.request.children.size}")
                        Resolved(selection.request,
                            MediaRequestContext(selection.request, catalog.snapshot(page, privateMode, tabId), userAgent))
                    }
                    is MediaRequestCatalog.Selection.Unavailable -> { reason = selection.reason; null }
                    is MediaRequestCatalog.Selection.Ambiguous -> { reason = "ambiguous-media-sources"; null }
                }
            } catch (error: Exception) {
                reason = error.javaClass.simpleName
                if (error is InterruptedException) Thread.currentThread().interrupt()
                null
            }
            GeckoWebEngine.uiHandler.post { result(resolved, reason) }
        }
    }
}