package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The contents of blob: downloads. WebView tells only the address of a download ([android.webkit.DownloadListener]),
 * and a blob: address exists only in the page, so the frames read the blob for the app: `blob_downloads.js` (a
 * document start script of every frame) says hello to [CHANNEL], and a download asks the frames of the origin of its
 * address for its size, type and name, then for its bytes a chunk at a time while the download service takes them
 * ([open]): array buffers where WebView sends them, base64 text elsewhere. The frames of all views are asked (a window
 * the page opens at a blob: address is a view of its own), on the main thread, which the reply proxies need.
 */
internal object WebViewBlobDownloads {
    private const val TAG = "WebViewBlobDownloads"
    private const val CHANNEL = "tvBroBlobs"
    private const val HELLO = "hello"
    private const val CHUNK_SIZE = 1 shl 20
    /** A frame that does not answer is of a document that is gone */
    private const val ANSWER_TIMEOUT_MS = 5_000L
    private const val CHUNK_TIMEOUT_MS = 30_000L

    /** What the page tells of a blob */
    class Blob(val size: Long, val type: String?, val name: String?)

    private val handler = Handler(Looper.getMainLooper())
    private val frames = BlobFrames<JavaScriptReplyProxy>()
    private val transfers = HashMap<Int, Transfer>()
    private var nextId = 1
    private var script: String? = null

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    private val binary: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)

    fun install(webView: WebView) {
        if (!isSupported) return
        WebViewCompat.addWebMessageListener(webView, CHANNEL, setOf("*")) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            onMessage(message, sourceOrigin, isMainFrame, replyProxy)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, script(webView.context), setOf("*"))
    }

    /**
     * Asks the frames for the blob of [url]. [onResult] gets on the main thread what the page tells of it and its
     * contents (read them on another thread, close them when done), or nulls when no frame gives it
     */
    fun open(url: String, onResult: (Blob?, InputStream?) -> Unit) {
        val origin = BlobFrames.originOf(url)
        val candidates = if (isSupported && origin != null) frames.candidates(origin) else emptyList()
        val transfer = Transfer(nextId++, url, ArrayDeque(candidates), binary, onResult)
        transfers[transfer.id] = transfer
        askNext(transfer)
    }

    private class Transfer(val id: Int, val url: String, val candidates: ArrayDeque<JavaScriptReplyProxy>,
                           /** Chunks come as array buffers */
                           val binary: Boolean, val onResult: (Blob?, InputStream?) -> Unit) {
        var frame: JavaScriptReplyProxy? = null
        var timeout: Runnable? = null
        var started = false
        /** Chunks (ByteArray) or the failure (IOException) the page gave */
        val chunks = LinkedBlockingQueue<Any>()
    }

    private fun askNext(transfer: Transfer) {
        transfer.timeout?.let { handler.removeCallbacks(it) }
        val frame = transfer.candidates.removeFirstOrNull()
        if (frame == null) {
            Log.w(TAG, "No frame gave ${transfer.url}")
            transfers.remove(transfer.id)
            transfer.onResult(null, null)
            return
        }
        transfer.frame = frame
        val timeout = Runnable { askNext(transfer) }
        transfer.timeout = timeout
        handler.postDelayed(timeout, ANSWER_TIMEOUT_MS)
        send(frame, JSONObject().put("start", transfer.id).put("url", transfer.url))
    }

    private fun send(frame: JavaScriptReplyProxy, message: JSONObject) {
        try {
            frame.postMessage(message.toString())
        } catch (e: Exception) {
            //a frame that is gone
            Log.w(TAG, "Can not send to a frame", e)
        }
    }

    private fun onMessage(message: WebMessageCompat, origin: Uri, isMainFrame: Boolean, frame: JavaScriptReplyProxy) {
        if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            val data = message.arrayBuffer
            if (data.size < 4) return
            val id = (data[0].toInt() and 0xff shl 24) or (data[1].toInt() and 0xff shl 16) or
                    (data[2].toInt() and 0xff shl 8) or (data[3].toInt() and 0xff)
            val transfer = transfers[id]?.takeIf { it.started && it.frame == frame } ?: return
            transfer.chunks.offer(data.copyOfRange(4, data.size))
            return
        }
        val text = message.data ?: return
        if (text == HELLO) {
            frames.onHello(origin.toString(), isMainFrame, frame)
            return
        }
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return
        }
        //only the frame asked answers for a transfer
        val transfer = transfers[json.optInt("id", -1)]?.takeIf { it.frame == frame } ?: return
        val error = if (json.has("error")) json.optString("error") else null
        if (!transfer.started) {
            if (error != null) {
                Log.w(TAG, "A frame did not give ${transfer.url}: $error")
                askNext(transfer)
                return
            }
            transfer.timeout?.let { handler.removeCallbacks(it) }
            transfer.started = true
            val blob = Blob(json.optLong("size"), json.optString("type").takeIf { json.has("type") && !json.isNull("type") && it.isNotEmpty() },
                json.optString("name").takeIf { json.has("name") && !json.isNull("name") && it.isNotBlank() })
            transfer.onResult(blob, BlobInputStream(transfer, blob.size))
            return
        }
        if (error != null) {
            transfer.chunks.offer(IOException("The page did not give the blob: $error"))
            return
        }
        try {
            transfer.chunks.offer(Base64.decode(json.optString("data"), Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            transfer.chunks.offer(IOException("Wrong data of the blob", e))
        }
    }

    /** The bytes of the blob, asked for a chunk at a time as they are read (on another thread than the main one) */
    private class BlobInputStream(private val transfer: Transfer, private val size: Long) : InputStream() {
        private var offset = 0L
        private var buffer: ByteArray? = null
        private var position = 0
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Closed")
            if (len == 0) return 0
            var chunk = buffer
            if (chunk == null || position >= chunk.size) {
                if (offset >= size) return -1
                val length = min(CHUNK_SIZE.toLong(), size - offset).toInt()
                val request = JSONObject().put("chunk", transfer.id).put("offset", offset).put("length", length)
                    .put("binary", transfer.binary)
                handler.post { transfer.frame?.let { send(it, request) } }
                val next = transfer.chunks.poll(CHUNK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: throw IOException("The page did not give the blob in time")
                if (next is IOException) throw next
                chunk = next as ByteArray
                if (chunk.isEmpty()) throw IOException("The blob ended before its size")
                offset += chunk.size
                buffer = chunk
                position = 0
            }
            val count = min(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, count)
            position += count
            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            buffer = null
            handler.post {
                transfers.remove(transfer.id)
                transfer.frame?.let { send(it, JSONObject().put("end", transfer.id)) }
            }
        }
    }

    private fun script(context: Context): String = script
        ?: context.assets.open("blob_downloads.js").bufferedReader().use { it.readText() }.also { script = it }
}

/** Contents that can not be read: the download fails with [message] */
internal class FailedInputStream(private val message: String) : InputStream() {
    override fun read(): Int = throw IOException(message)
}
