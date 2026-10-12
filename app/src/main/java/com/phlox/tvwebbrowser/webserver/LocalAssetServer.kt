package com.phlox.tvwebbrowser.webserver

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.phlox.tvwebbrowser.Config
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Minimal loopback-only HTTP server that exposes bundled assets over http://127.0.0.1.
 *
 * It exists because GeckoView can not load app assets directly (resource:// and file:// both
 * fail for arbitrary HTML). Serving the same files WebView serves through shouldInterceptRequest
 * lets both engines render the identical built-in home page.
 *
 * Currently only the home page is exposed, at [HOME_ROUTE_PREFIX], backed by the
 * "pages/home/" asset directory.
 */
object LocalAssetServer {
    private val TAG = LocalAssetServer::class.java.simpleName

    /** Fixed so that persisted tab URLs stay valid across restarts. */
    const val PORT = 46321
    private const val HOME_ROUTE = "appcontent/home/"
    private const val HOME_ASSETS = "pages/home/"

    var homePageUrl: String? = null
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientExecutor = Executors.newFixedThreadPool(4)

    fun start(context: Context) {
        if (acceptThread != null) return
        if (!isMainProcess(context)) {
            Log.d(TAG, "Not the main process, skipping local asset server")
            return
        }
        val appContext = context.applicationContext
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16)
            serverSocket = socket
            homePageUrl = "http://127.0.0.1:$PORT/$HOME_ROUTE"
            Config.localHomePageUrl = homePageUrl
            val thread = Thread({ acceptLoop(appContext, socket) }, "LocalAssetServer")
            thread.isDaemon = true
            acceptThread = thread
            thread.start()
            Log.i(TAG, "Started at $homePageUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start local asset server, falling back to remote home page", e)
        }
    }

    private fun isMainProcess(context: Context): Boolean {
        val processName = currentProcessName() ?: return true
        return processName == context.packageName
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return try {
            File("/proc/self/cmdline").readText().trim('\u0000', '\n', ' ')
        } catch (e: Exception) {
            null
        }
    }

    private fun acceptLoop(context: Context, socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            clientExecutor.execute { handleClient(context, client) }
        }
    }

    private fun handleClient(context: Context, client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                var path = parts[1]
                //drain headers until the blank line
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val out = BufferedOutputStream(c.getOutputStream())
                val headOnly = method == "HEAD"
                if (method != "GET" && !headOnly) {
                    writeResponse(out, 405, "text/plain", "Method Not Allowed".toByteArray(), false)
                    return
                }

                val queryIndex = path.indexOf('?')
                if (queryIndex >= 0) path = path.substring(0, queryIndex)
                path = try {
                    URLDecoder.decode(path, "UTF-8")
                } catch (e: Exception) {
                    path
                }

                val assetPath = routeToAsset(path)
                val bytes = assetPath?.let {
                    try {
                        context.assets.open(it).use { input -> input.readBytes() }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (assetPath == null || bytes == null) {
                    writeResponse(out, 404, "text/plain", "Not Found".toByteArray(), headOnly)
                    return
                }

                var contentType = URLConnection.guessContentTypeFromName(assetPath)
                    ?: "application/octet-stream"
                if (assetPath.endsWith(".svg")) contentType = "image/svg+xml"
                writeResponse(out, 200, contentType, bytes, headOnly)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleClient failed: $e")
        }
    }

    private fun routeToAsset(path: String): String? {
        val normalized = path.trimStart('/')
        if (!normalized.startsWith(HOME_ROUTE)) return null
        var relative = normalized.substring(HOME_ROUTE.length)
        if (relative.isEmpty()) relative = "index.html"
        if (relative.contains("..")) return null
        return HOME_ASSETS + relative
    }

    private fun writeResponse(
        out: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean
    ) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (!headOnly) out.write(body)
        out.flush()
    }
}
