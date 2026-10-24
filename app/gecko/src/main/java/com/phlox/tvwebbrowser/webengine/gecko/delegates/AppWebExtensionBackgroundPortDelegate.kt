package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.phlox.tvwebbrowser.webengine.EngineAdBlocker
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.Executors

/**
 * The port of the background script of the extension, one for all tabs (created on the main thread).
 *
 * The script decides about requests itself (`adblock_engine.js`, as [EngineAdBlocker.shouldBlock] does) once it
 * has the request filters and the site settings, which this sends to it: the filters when the script does not
 * have those of the app (it keeps them in IndexedDB between starts), in chunks, each after the script took the
 * previous one; the settings whenever they change. Until then the script asks here for each request
 * (`webRequest.onBeforeRequest`), and each waits for the answer or the timeout of the script.
 */
class AppWebExtensionBackgroundPortDelegate(private val port: WebExtension.Port) : WebExtension.PortDelegate {
    private val blocker: EngineAdBlocker? = WebEngineFactory.adBlocker
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The fingerprint of the filters the script has, as it told ("" none); null until it tells */
    private var scriptFingerprint: String? = null
    /** Filters the script could not load: not sent again */
    private var failedFingerprint: String? = null
    private var transfer: Transfer? = null
    private var disconnected = false

    private class Transfer(val fingerprint: String, val bytes: ByteArray) {
        val count = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
    }

    private val changeListener = Runnable { mainHandler.post { sync() } }

    init {
        blocker?.addChangeListener(changeListener)
        sync()
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val msgJson = message as? JSONObject ?: return
        when (msgJson.optString("action")) {
            "onBeforeRequest" -> onBeforeRequest(msgJson.optJSONObject("details") ?: return, port)
            "scriptEngine" -> {
                scriptFingerprint = msgJson.optString("fingerprint")
                if (msgJson.optBoolean("failed")) failedFingerprint = msgJson.optString("failedFingerprint")
                sync()
            }
            "engineChunkAck" -> {
                val t = transfer ?: return
                if (msgJson.optString("fingerprint") != t.fingerprint) return
                val next = msgJson.optInt("index") + 1
                if (next < t.count) sendChunk(t, next)
            }
        }
    }

    /** Sends what the script lacks: the config, the site settings, the filters (main thread) */
    private fun sync() {
        if (disconnected) return
        val blocker = blocker ?: return
        //main frames go to the app only while filters may block pages (the script decides them itself later)
        port.postMessage(JSONObject().put("action", "config").put("checkPages", blocker.blocksPages))
        blocker.siteSettings?.let { s ->
            val hosts = JSONObject()
            for ((host, enabled) in s.hosts) hosts.put(host, enabled)
            port.postMessage(JSONObject().put("action", "siteSettings").put("enabled", s.enabled).put("hosts", hosts)
                .put("homePageUrl", s.homePageUrl))
        }
        val have = scriptFingerprint ?: return
        val current = blocker.scriptEngineFingerprint?.toString()
        when {
            current == null -> {
                transfer = null
                if (have.isNotEmpty()) port.postMessage(JSONObject().put("action", "engineNone"))
                scriptFingerprint = ""
            }
            current == have -> {
                transfer = null
                port.postMessage(JSONObject().put("action", "engineCurrent").put("fingerprint", current))
            }
            current == failedFingerprint || transfer?.fingerprint == current -> {}
            else -> startTransfer()
        }
    }

    private fun startTransfer() {
        val blocker = blocker ?: return
        executor.execute {
            val file = try {
                blocker.scriptEngineFile()
            } catch (e: Exception) {
                Log.e(TAG, "request filters for the extension", e)
                null
            } ?: return@execute
            mainHandler.post {
                val fingerprint = file.fingerprint.toString()
                //the filters changed again meanwhile: the next sync sends those
                if (disconnected || blocker.scriptEngineFingerprint?.toString() != fingerprint) return@post
                if (transfer?.fingerprint == fingerprint) return@post
                Log.i(TAG, "sending the request filters to the extension: ${file.bytes.size / 1024} KB")
                val t = Transfer(fingerprint, file.bytes)
                transfer = t
                sendChunk(t, 0)
            }
        }
    }

    /** Encodes the chunk on the executor, posts it on the main thread (the script answers each one) */
    private fun sendChunk(t: Transfer, index: Int) {
        executor.execute {
            val from = index * CHUNK_SIZE
            val data = Base64.encodeToString(t.bytes, from, minOf(CHUNK_SIZE, t.bytes.size - from), Base64.NO_WRAP)
            mainHandler.post {
                if (disconnected || transfer !== t) return@post
                port.postMessage(JSONObject().put("action", "engineChunk").put("fingerprint", t.fingerprint)
                    .put("index", index).put("count", t.count).put("data", data))
                if (index == t.count - 1) transfer = null
            }
        }
    }

    private fun onBeforeRequest(details: JSONObject, port: WebExtension.Port) {
        //the script keys the request by this id; without it there is nothing to answer
        val requestId = details.opt("requestId") ?: return
        var block = false
        try {
            block = isAd(details)
        } catch (e: Exception) {
            Log.e(TAG, "onBeforeRequest", e)
        } finally {
            val data = JSONObject().put("requestId", requestId).put("block", block)
            port.postMessage(JSONObject().put("action", "onResolveRequest").put("data", data))
        }
    }

    private fun isAd(details: JSONObject): Boolean {
        val blocker = WebEngineFactory.adBlocker ?: return false
        val url = details.optString("url").ifEmpty { return false }
        val type = details.optString("type")
        //a request the user started (the address bar) has no origin
        val document = details.optString("documentUrl").ifEmpty { details.optString("originUrl") }.ifEmpty { null }
        val top = details.optString("topUrl").ifEmpty { null } ?: document
        return blocker.shouldBlock(Uri.parse(url), type, null, type == "main_frame", document?.let(Uri::parse),
            top?.let(Uri::parse))
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        disconnected = true
        transfer = null
        blocker?.removeChangeListener(changeListener)
    }

    companion object {
        val TAG: String = AppWebExtensionBackgroundPortDelegate::class.java.simpleName
        /** Bytes of the filters per message (base64 makes a third more) */
        private const val CHUNK_SIZE = 512 * 1024
        private val executor = Executors.newSingleThreadExecutor()
    }
}
