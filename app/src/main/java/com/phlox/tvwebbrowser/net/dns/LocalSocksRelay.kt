package com.phlox.tvwebbrowser.net.dns

import android.util.Log
import com.phlox.tvwebbrowser.BuildConfig
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutorService

/**
 * A SOCKS5 proxy (RFC 1928, CONNECT without a login) on the loopback address that finds the addresses of the
 * names it is given with [resolver] and then only copies bytes both ways: TLS goes through it untouched. WebView
 * gives a SOCKS5 proxy the names of the sites, never their addresses (Chromium resolves them on the proxy side),
 * so pages find sites through the DNS of the [resolver].
 *
 * Other apps of the device could use it too (Chromium can not log in to a SOCKS proxy): it listens on a port the
 * system chooses, and connects to no loopback, wildcard or multicast address.
 */
class LocalSocksRelay(
    private val executor: ExecutorService,
    /** For tests: the targets may be on the loopback address */
    private val allowLoopbackTargets: Boolean = false
) {
    /** Finds the addresses of a name; changed without dropping the connections */
    @Volatile
    var resolver: ((String) -> List<InetAddress>)? = null

    private var server: ServerSocket? = null
    private val connections: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    /** The port it listens on, 0 while it is stopped */
    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    @Throws(IOException::class)
    fun start(): Int {
        server?.let { return it.localPort }
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        server = socket
        port = socket.localPort
        executor.execute { accept(socket) }
        Log.i(TAG, "Listening on $LOOPBACK:$port")
        return port
    }

    /** Closes the port and all connections (pages connect again through what replaces it) */
    @Synchronized
    fun stop() {
        val socket = server ?: return
        server = null
        port = 0
        closeQuietly(socket)
        dropConnections()
        Log.i(TAG, "Stopped")
    }

    /** Closes the connections made so far (pages connect again, finding the addresses anew) */
    fun dropConnections() {
        connections.toList().forEach(::closeQuietly)
        connections.clear()
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!server.isClosed) Log.e(TAG, "Can not accept", e)
                return
            }
            try {
                executor.execute { serve(client) }
            } catch (e: Exception) {
                closeQuietly(client)
            }
        }
    }

    private fun serve(client: Socket) {
        connections += client
        var upstream: Socket? = null
        var link: Link? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            if (!greet(input, output)) return
            val target = readRequest(input, output) ?: return
            upstream = connect(target, output) ?: return
            connections += upstream
            client.soTimeout = 0
            output.write(byteArrayOf(VERSION, REPLY_OK, 0, ATYP_IPV4, 0, 0, 0, 0, 0, 0))
            output.flush()
            val started = Link(client, upstream)
            link = started
            executor.execute { started.pump(upstream.getInputStream(), client) }
            started.pump(input, upstream)
        } catch (e: Exception) {
            //the page or the site closed the connection, or the relay stopped
            link?.close()
        } finally {
            if (link == null) {
                closeQuietly(client)
                connections -= client
                upstream?.let {
                    closeQuietly(it)
                    connections -= it
                }
            }
        }
    }

    /** The version and the methods; only "no login" is taken */
    private fun greet(input: DataInputStream, output: OutputStream): Boolean {
        if (input.readByte() != VERSION) return false
        val methods = ByteArray(input.readUnsignedByte())
        input.readFully(methods)
        if (methods.none { it == METHOD_NONE }) {
            output.write(byteArrayOf(VERSION, METHOD_UNACCEPTABLE))
            return false
        }
        output.write(byteArrayOf(VERSION, METHOD_NONE))
        output.flush()
        return true
    }

    private class Target(val host: String, val port: Int, val address: InetAddress?)

    private fun readRequest(input: DataInputStream, output: OutputStream): Target? {
        if (input.readByte() != VERSION) return null
        val command = input.readByte()
        input.readByte()
        val atyp = input.readByte()
        val target = when (atyp) {
            ATYP_IPV4, ATYP_IPV6 -> {
                val bytes = ByteArray(if (atyp == ATYP_IPV4) 4 else 16)
                input.readFully(bytes)
                val address = InetAddress.getByAddress(bytes)
                Target(address.hostAddress ?: "", input.readUnsignedShort(), address)
            }
            ATYP_DOMAIN -> {
                val name = ByteArray(input.readUnsignedByte())
                input.readFully(name)
                Target(String(name, Charsets.US_ASCII), input.readUnsignedShort(), null)
            }
            else -> {
                reply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                return null
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "CONNECT atyp=$atyp ${target.host}:${target.port}")
        if (command != COMMAND_CONNECT) {
            reply(output, REPLY_COMMAND_NOT_SUPPORTED)
            return null
        }
        return target
    }

    /** The first address of the target that takes the connection, IPv4 ones first */
    private fun connect(target: Target, output: OutputStream): Socket? {
        val addresses = try {
            target.address?.let { listOf(it) } ?: resolve(target.host)
        } catch (e: UnknownHostException) {
            reply(output, REPLY_HOST_UNREACHABLE)
            return null
        }
        var refused = false
        for (address in addresses.filterNot(::isForbidden).take(MAX_ATTEMPTS)) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: IOException) {
                refused = refused || e is java.net.ConnectException
                closeQuietly(socket)
            }
        }
        reply(output, if (refused) REPLY_CONNECTION_REFUSED else REPLY_HOST_UNREACHABLE)
        return null
    }

    private fun resolve(host: String): List<InetAddress> {
        if (isIpLiteral(host)) return listOf(InetAddress.getByName(if (host.contains(':')) "[$host]" else host))
        val resolver = resolver ?: return InetAddress.getAllByName(host).toList()
        return resolver(host)
    }

    private fun isForbidden(address: InetAddress) =
        (address.isLoopbackAddress && !allowLoopbackTargets) || address.isAnyLocalAddress || address.isMulticastAddress

    /** The two sockets of a connection: closed when both ways ended, or one of them failed */
    private inner class Link(private val client: Socket, private val server: Socket) {
        private val ended = AtomicInteger()

        /** Copies [input] to [to] until it ends, then ends that way only (the other may still send) */
        fun pump(input: InputStream, to: Socket) {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                val output = to.getOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                to.shutdownOutput()
            } catch (e: IOException) {
                close()
                return
            }
            if (ended.incrementAndGet() == 2) close()
        }

        fun close() {
            closeQuietly(client)
            closeQuietly(server)
            connections -= client
            connections -= server
        }
    }

    private fun reply(output: OutputStream, code: Byte) {
        try {
            output.write(byteArrayOf(VERSION, code, 0, ATYP_IPV4, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (e: IOException) {
            //the page went away
        }
    }

    private fun closeQuietly(socket: Closeable) {
        try {
            socket.close()
        } catch (e: IOException) {
            //closed
        }
    }

    companion object {
        private const val TAG = "LocalSocksRelay"
        const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 64
        private const val BUFFER_SIZE = 32 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_ATTEMPTS = 4

        private const val VERSION: Byte = 5
        private const val METHOD_NONE: Byte = 0
        private const val METHOD_UNACCEPTABLE: Byte = 0xFF.toByte()
        private const val COMMAND_CONNECT: Byte = 1
        private const val ATYP_IPV4: Byte = 1
        private const val ATYP_DOMAIN: Byte = 3
        private const val ATYP_IPV6: Byte = 4
        private const val REPLY_OK: Byte = 0
        private const val REPLY_HOST_UNREACHABLE: Byte = 4
        private const val REPLY_CONNECTION_REFUSED: Byte = 5
        private const val REPLY_COMMAND_NOT_SUPPORTED: Byte = 7
        private const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 8

        /** An IPv4 or IPv6 address written out: no lookup is needed */
        fun isIpLiteral(host: String): Boolean =
            host.contains(':') || (host.isNotEmpty() && host.all { it.isDigit() || it == '.' } && host.count { it == '.' } == 3)
    }
}
