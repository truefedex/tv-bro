package com.phlox.tvwebbrowser.net.dns

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class LocalSocksRelayTest {
    private val executor = Executors.newCachedThreadPool()
    private val echo = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val resolved = ArrayList<String>()
    private val relay = LocalSocksRelay(executor, allowLoopbackTargets = true).apply {
        resolver = { host ->
            synchronized(resolved) { resolved += host }
            if (host == "echo.test") listOf(InetAddress.getByName("127.0.0.1")) else throw UnknownHostException(host)
        }
    }

    init {
        //echoes each connection until its client ends it
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val socket = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { it.getInputStream().copyTo(it.getOutputStream()) }
                }
            }
        }
    }

    @After
    fun tearDown() {
        relay.stop()
        echo.close()
        executor.shutdownNow()
    }

    @Test
    fun namesAreFoundByTheResolverAndBytesGoBothWays() {
        val port = relay.start()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            assertArrayEquals(byteArrayOf(5, 0), handshake(input, output))
            output.write(connectRequest("echo.test", echo.localPort))
            val reply = ByteArray(10).also { input.readFully(it) }
            assertEquals(0, reply[1].toInt())
            output.write("hello".toByteArray())
            assertEquals("hello", String(ByteArray(5).also { input.readFully(it) }))
            //ending one way closes the other once the server ended it too
            socket.shutdownOutput()
            assertEquals(-1, input.read())
        }
        assertEquals(listOf("echo.test"), resolved)
    }

    @Test
    fun anUnknownNameIsAnUnreachableHost() {
        val port = relay.start()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            handshake(input, socket.getOutputStream())
            socket.getOutputStream().write(connectRequest("nowhere.test", 443))
            val reply = ByteArray(10).also { input.readFully(it) }
            assertEquals(4, reply[1].toInt())
        }
    }

    @Test
    fun addressesWrittenAsNamesAreNotResolved() {
        val port = relay.start()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            handshake(input, socket.getOutputStream())
            //WebView gives addresses as names too
            socket.getOutputStream().write(connectRequest("127.0.0.1", echo.localPort))
            assertEquals(0, ByteArray(10).also { input.readFully(it) }[1].toInt())
        }
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun aLoginIsNotTaken() {
        val port = relay.start()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            //only user name and password offered
            socket.getOutputStream().write(byteArrayOf(5, 1, 2))
            val reply = ByteArray(2).also { DataInputStream(socket.getInputStream()).readFully(it) }
            assertEquals(0xFF.toByte(), reply[1])
        }
    }

    @Test
    fun loopbackTargetsAreRefusedOutsideTests() {
        val strict = LocalSocksRelay(executor).apply { resolver = { listOf(InetAddress.getByName("127.0.0.1")) } }
        val port = strict.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream())
                handshake(input, socket.getOutputStream())
                socket.getOutputStream().write(connectRequest("echo.test", echo.localPort))
                assertFalse(ByteArray(10).also { input.readFully(it) }[1].toInt() == 0)
            }
        } finally {
            strict.stop()
        }
    }

    @Test
    fun stoppingClosesThePort() {
        val port = relay.start()
        relay.stop()
        assertEquals(0, relay.port)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }

    @Test
    fun ipLiterals() {
        assertTrue(LocalSocksRelay.isIpLiteral("1.1.1.1"))
        assertTrue(LocalSocksRelay.isIpLiteral("2001:db8::1"))
        assertFalse(LocalSocksRelay.isIpLiteral("1.1.1"))
        assertFalse(LocalSocksRelay.isIpLiteral("example.com"))
        assertFalse(LocalSocksRelay.isIpLiteral("123.example"))
    }

    private fun handshake(input: DataInputStream, output: java.io.OutputStream): ByteArray {
        output.write(byteArrayOf(5, 1, 0))
        return ByteArray(2).also { input.readFully(it) }
    }

    private fun connectRequest(host: String, port: Int): ByteArray =
        byteArrayOf(5, 1, 0, 3, host.length.toByte()) + host.toByteArray() +
            byteArrayOf((port shr 8).toByte(), port.toByte())
}
