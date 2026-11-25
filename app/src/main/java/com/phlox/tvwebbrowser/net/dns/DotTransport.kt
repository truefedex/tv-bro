package com.phlox.tvwebbrowser.net.dns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS over TLS (RFC 7858) to [address], whose certificate must be valid for [serverName]: for servers that answer
 * DNS over HTTPS only over HTTP/2, which the HTTP client of Android does not speak. One connection, kept open
 * and opened again when the server closed it; questions go one at a time.
 */
class DotTransport(
    private val address: String,
    private val serverName: String,
    private val port: Int = PORT
) : DohResolver.Transport {
    private var socket: SSLSocket? = null

    @Synchronized
    override fun exchange(query: ByteArray): ByteArray {
        //a kept connection may have been closed by the server meanwhile: one more try on a new one
        for (attempt in 0..1) {
            val reused = socket != null
            val connection = socket ?: open().also { socket = it }
            try {
                val output = DataOutputStream(connection.outputStream)
                output.writeShort(query.size)
                output.write(query)
                output.flush()
                val input = DataInputStream(connection.inputStream)
                val answer = ByteArray(input.readUnsignedShort())
                input.readFully(answer)
                return answer
            } catch (e: IOException) {
                close()
                if (!reused || attempt == 1) throw e
            }
        }
        throw IOException("Unreachable")
    }

    private fun open(): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(InetAddress.getByName(address), port), TIMEOUT_MS)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, serverName, port, true) as SSLSocket
            ssl.soTimeout = TIMEOUT_MS
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(serverName, ssl.session)) {
                ssl.close()
                throw SSLPeerUnverifiedException("The certificate of $address is not for $serverName")
            }
            return ssl
        } catch (e: IOException) {
            raw.close()
            throw e
        }
    }

    @Synchronized
    fun close() {
        try {
            socket?.close()
        } catch (e: IOException) {
            //closed
        }
        socket = null
    }

    companion object {
        const val PORT = 853
        private const val TIMEOUT_MS = 5000
    }
}
