package com.phlox.tvwebbrowser.webengine.common

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.webengine.webview.WebViewEx
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object HomePageHelper {
    private val TAG = HomePageHelper::class.java.simpleName

    fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        Log.d(TAG, "shouldInterceptRequest: " + request.url)
        val url = request.url.toString()
        //check is scheme is favicon
        if (request.url.scheme == "favicon") {
            val host = request.url.host ?: return null
            val favicon = runBlocking {
                FaviconsPool.get(host)
            }
            if (favicon != null) {
                Log.d(TAG, "shouldInterceptRequest: favicon found for $host")
                val bytes = ByteArrayOutputStream()
                favicon.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                return WebResourceResponse("image/png",
                    "utf-8", bytes.toByteArray().inputStream())
            } else {
                return WebResourceResponse(null, null, 404, "Not Found", null, null)
            }
        } else if (url.startsWith(WebViewEx.HOME_PAGE_URL) &&
            (url.endsWith(".svg") || url.endsWith(".png"))) {
            //load images of tvbro.phlox.dev from assets for offline mode
            val data = TVBro.instance.assets.open("pages/home" + request.url.path!!.replace("/appcontent/home", "")).use { it.readBytes() }
            var imageType = url.substring(url.lastIndexOf(".") + 1)
            if (imageType == "svg") {
                imageType = "svg+xml"
            }
            return WebResourceResponse("image/" + imageType,
                "utf-8", data.inputStream())
        } else if (url.endsWith("ip-api/json/")) {
            //one-time load of http://ip-api.com for country detection, but it blocked by cors policy so we use local proxy
            val urlConnection = URL("http://ip-api.com/json/" ).openConnection() as HttpURLConnection
            val data = urlConnection.inputStream.use { it.readBytes() }
            return WebResourceResponse(urlConnection.contentType,
                "utf-8", data.inputStream())
        }
        return null
    }
}