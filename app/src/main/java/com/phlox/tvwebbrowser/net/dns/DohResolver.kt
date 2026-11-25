package com.phlox.tvwebbrowser.net.dns

import android.util.Log
import com.phlox.tvwebbrowser.net.DnsSettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * Finds the addresses of names through a DNS-over-HTTPS server (RFC 8484), as GeckoView's resolver does in the
 * mode the app gives it: the device's DNS only while the server does not answer, never for a name the server
 * says is not there. Answers are kept for their time to live (within [MIN_TTL_SECONDS] and [MAX_TTL_SECONDS]),
 * and a name asked again while it is asked waits for that answer. Thread-safe.
 */
class DohResolver(
    private val transport: Transport,
    private val executor: Executor,
    private val systemResolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val nanoTime: () -> Long = System::nanoTime
) {
    /** Sends a question to the server and gives its answer */
    fun interface Transport {
        @Throws(IOException::class)
        fun exchange(query: ByteArray): ByteArray
    }

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>>()
    /** Until when the server is not asked, after it did not answer ([DOWN_SECONDS]); null while it answers */
    @Volatile
    private var downUntil: Long? = null

    /** The addresses of [host], IPv4 ones first; throws [UnknownHostException] when it has none */
    @Throws(UnknownHostException::class)
    fun resolve(host: String): List<InetAddress> {
        val name = host.trimEnd('.').lowercase()
        cache[name]?.let { entry ->
            if (nanoTime() - entry.expiresAt < 0) return entry.addresses.ifEmpty { throw UnknownHostException(host) }
            cache.remove(name, entry)
        }
        val future = CompletableFuture<List<InetAddress>>()
        val asked = pending.putIfAbsent(name, future)
        if (asked != null) return await(asked, host)
        try {
            future.complete(lookUp(name))
        } catch (e: Exception) {
            future.completeExceptionally(e)
        } finally {
            pending.remove(name, future)
        }
        return await(future, host)
    }

    private fun await(future: CompletableFuture<List<InetAddress>>, host: String): List<InetAddress> = try {
        future.get().ifEmpty { throw UnknownHostException(host) }
    } catch (e: ExecutionException) {
        throw (e.cause as? UnknownHostException) ?: UnknownHostException(host).apply { initCause(e.cause) }
    }

    /** A and AAAA at once; the device's DNS when the server answered neither */
    private fun lookUp(name: String): List<InetAddress> {
        downUntil?.let { if (nanoTime() - it < 0) return systemResolver(name) }
        val ipv6 = CompletableFuture.supplyAsync({ ask(name, DnsMessage.TYPE_AAAA) }, executor)
        val ipv4 = runCatching { ask(name, DnsMessage.TYPE_A) }
        val ipv6Result = runCatching {
            try {
                ipv6.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        val answers = listOfNotNull(ipv4.getOrNull(), ipv6Result.getOrNull())
        if (answers.isEmpty()) {
            //the server did not answer: the device's DNS for a while, not kept (the server may answer later)
            if (downUntil == null) {
                Log.w(TAG, "The DNS server did not answer: the DNS of the device is used", ipv4.exceptionOrNull())
            }
            downUntil = nanoTime() + DOWN_SECONDS * 1_000_000_000L
            return systemResolver(name)
        }
        downUntil = null
        val addresses = answers.flatMap { it.addresses }.sortedBy { if (it is Inet4Address) 0 else 1 }
        val ttl = if (addresses.isEmpty()) NEGATIVE_TTL_SECONDS
            else answers.filter { it.addresses.isNotEmpty() }.minOf { it.ttlSeconds }
                .coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        if (cache.size >= MAX_CACHED_NAMES) cache.clear()
        cache[name] = Entry(addresses, nanoTime() + ttl * 1_000_000_000L)
        return addresses
    }

    private fun ask(name: String, type: Int): DnsMessage.Answer {
        val answer = DnsMessage.parse(transport.exchange(DnsMessage.query(name, type)), type)
        if (answer.rcode != DnsMessage.RCODE_OK && answer.rcode != DnsMessage.RCODE_NAME_ERROR) {
            throw IOException("DNS server error ${answer.rcode} for $name")
        }
        return answer
    }

    /**
     * POSTs questions to [url] directly (never through a proxy of the app, also not the relay that asks it);
     * the platform keeps the connections alive. Its HTTP client speaks only HTTP/1.1 (see [DotTransport]).
     */
    class HttpsTransport(private val url: URL) : Transport {
        override fun exchange(query: ByteArray): ByteArray {
            val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", MEDIA_TYPE)
                connection.setRequestProperty("Accept", MEDIA_TYPE)
                connection.setFixedLengthStreamingMode(query.size)
                connection.outputStream.use { it.write(query) }
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw IOException("DNS server answered $status")
                return connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_ANSWER_SIZE) throw IOException("DNS answer too big")
                    }
                    out.toByteArray()
                }
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
        }
    }

    companion object {
        /** How the questions of [dns] go to its server: over TLS where it answers HTTPS only over HTTP/2 */
        fun transportOf(dns: DnsSettings): Transport {
            val address = dns.bootstrapAddress
            val tlsName = dns.tlsServerName
            return if (address != null && tlsName != null) DotTransport(address, tlsName) else HttpsTransport(urlOf(dns))
        }

        private const val TAG = "DohResolver"
        const val MIN_TTL_SECONDS = 60L
        const val MAX_TTL_SECONDS = 3600L
        /** How long a name without addresses is kept */
        const val NEGATIVE_TTL_SECONDS = 60L
        /** How long a server that did not answer is not asked */
        const val DOWN_SECONDS = 30L
        private const val MAX_CACHED_NAMES = 2000
        private const val TIMEOUT_MS = 5000
        private const val MAX_ANSWER_SIZE = 65535
        private const val MEDIA_TYPE = "application/dns-message"

        /**
         * The address the questions of [dns] go to: that of its server with the IP address in place of the name,
         * so that the name is not asked of the device's DNS (the certificates of the servers of
         * [DnsSettings.Provider] are valid for their IP addresses)
         */
        fun urlOf(dns: DnsSettings): URL {
            val address = dns.bootstrapAddress ?: return URL(dns.url)
            val uri = URI(dns.url)
            val host = if (address.contains(':')) "[$address]" else address
            return URI(uri.scheme, uri.userInfo, host, uri.port, uri.path, uri.query, null).toURL()
        }
    }
}
