package com.phlox.tvwebbrowser.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Backpressured streaming transport. Re-evaluate credentials for every redirect destination. */
@UnstableApi
class ScopedMediaDataSource(private val context: MediaRequestContext) : DataSource {
    class Factory(private val context: MediaRequestContext) : DataSource.Factory {
        override fun createDataSource(): DataSource = ScopedMediaDataSource(context)
    }
    companion object {
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
    private var delegate: HttpDataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners.add(transferListener) }
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) throw IOException("Only GET media requests are supported")
        var url = dataSpec.uri.toString()
        for (redirect in 0..10) {
            if (!MediaRequestCatalog.isHttp(url)) throw IOException("Unsupported media URI scheme")
            val source = OkHttpDataSource.Factory(client).setDefaultRequestProperties(context.headersFor(url)).createDataSource()
            listeners.forEach(source::addTransferListener)
            delegate = source
            val request = dataSpec.buildUpon().setUri(url).setHttpRequestHeaders(emptyMap()).build()
            try { return source.open(request) }
            catch (error: HttpDataSource.InvalidResponseCodeException) {
                source.close()
                delegate = null
                if (error.responseCode !in setOf(301, 302, 303, 307, 308)) throw error
                val location = error.headerFields.entries.firstOrNull { it.key.equals("location", true) }?.value?.firstOrNull()
                    ?: throw IOException("Media redirect without location")
                val next = URI(url).resolve(location).toString()
                if (url.startsWith("https:", true) && next.startsWith("http:", true)) throw IOException("Insecure media redirect")
                url = next
            } catch (error: Exception) {
                source.close()
                delegate = null
                throw error
            }
        }
        throw IOException("Too many media redirects")
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IOException("Media data source is closed")
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { delegate?.close() } finally { delegate = null } }
}
