package com.phlox.tvwebbrowser.browser.adblock

import com.phlox.tvwebbrowser.net.AppProxy
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads filter lists (a fake one in tests) */
interface ListFetcher {
    sealed class Result {
        /** The server says the list did not change since its [ListMeta.etag] / [ListMeta.lastModified] */
        data object NotModified : Result()
        data class Downloaded(val etag: String?, val lastModified: String?) : Result()
    }

    /** Downloads the list at [url] into [destination], unless it did not change; [IOException] if it fails */
    fun fetch(url: String, etag: String?, lastModified: String?, destination: File): Result
}

class HttpListFetcher : ListFetcher {
    override fun fetch(url: String, etag: String?, lastModified: String?, destination: File): ListFetcher.Result {
        val connection = try {
            AppProxy.open(URL(url)) as? HttpURLConnection ?: throw IOException("not an http address: $url")
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            etag?.let { connection.setRequestProperty("If-None-Match", it) }
            lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return ListFetcher.Result.NotModified
                HttpURLConnection.HTTP_OK -> {}
                else -> throw IOException("HTTP $code for $url")
            }
            connection.inputStream.use { input -> destination.outputStream().use { input.copyTo(it) } }
            return ListFetcher.Result.Downloaded(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}
