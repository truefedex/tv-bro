package com.phlox.tvwebbrowser.webengine.gecko

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Associates a native media event with the browser's active tab, without a content script. */
object BrowserMediaBridge {
    data class Tab(val id: Int, val page: String, val privateMode: Boolean)
    private val handler = Handler(Looper.getMainLooper())
    private val sequence = AtomicInteger()
    private val pending = mutableMapOf<Int, CompletableFuture<Tab?>>()
    private var port: WebExtension.Port? = null

    fun connect(value: WebExtension.Port) { port = value }
    fun disconnect(value: WebExtension.Port) {
        if (port !== value) return
        port = null
        pending.values.forEach { it.complete(null) }
        pending.clear()
    }
    fun activeTab(): CompletableFuture<Tab?> {
        val future = CompletableFuture<Tab?>()
        handler.post {
            val connection = port
            if (connection == null) { future.complete(null); return@post }
            val id = sequence.incrementAndGet()
            pending[id] = future
            try { connection.postMessage(JSONObject().put("action", "activeMediaTab").put("requestId", id)) }
            catch (_: Exception) { pending.remove(id)?.complete(null) }
            handler.postDelayed({ pending.remove(id)?.complete(null) }, 3000)
        }
        return future
    }
    fun receive(message: JSONObject) {
        val id = message.optInt("requestId", -1)
        val tab = message.optJSONObject("tab")
        pending.remove(id)?.complete(tab?.let { Tab(it.getInt("id"), it.getString("url"), it.optBoolean("incognito")) })
    }
}