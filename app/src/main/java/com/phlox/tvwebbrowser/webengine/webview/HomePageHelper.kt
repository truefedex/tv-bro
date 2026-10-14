package com.phlox.tvwebbrowser.webengine.webview

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

object HomePageHelper {
    private val TAG = HomePageHelper::class.java.simpleName

    fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        Log.d(TAG, "shouldInterceptRequest: " + request.url)
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
        }
        return null
    }
}