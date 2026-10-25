package com.phlox.tvwebbrowser.sync

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SyncChannelTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val key = SyncCrypto.randomBytes(SyncCrypto.KEY_SIZE)

    @After
    fun tearDown() {
        server.close()
    }

    /** Runs the server side on its own thread; its result (or its failure) is the future's */
    private fun <T> serve(block: (Socket) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        Thread {
            try {
                server.accept().use { future.complete(block(it)) }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }.start()
        return future
    }

    private fun client(): Socket = Socket(server.inetAddress, server.localPort).apply { soTimeout = 5000 }

    private fun codeOf(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: SyncException) {
        e.code
    }

    private fun <T> CompletableFuture<T>.result(): T = try {
        get(5, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause!!
    }

    @Test
    fun messagesGoBothWaysWithTheKeyOfThePairing() {
        val served = serve { socket ->
            val accepted = SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) {
                if (it == "pairing") key else null
            }
            val request = accepted.channel.receive()
            accepted.channel.send(JSONObject().put("echo", request.getString("text")))
            accepted.pairingId
        }
        client().use { socket ->
            val channel = SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(), key, "pairing")
            channel.send(JSONObject().put("text", "привет"))
            assertEquals("привет", channel.receive().getString("echo"))
        }
        assertEquals("pairing", served.result())
    }

    @Test
    fun bytesGoBetweenMessages() {
        val chunk = SyncCrypto.randomBytes(100_000)
        val served = serve { socket ->
            val channel = SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { key }.channel
            val header = channel.receive().getString("name")
            val first = channel.receiveBytes()
            val end = channel.receiveBytes()
            channel.send(JSONObject().put("size", first.size).put("end", end.size))
            channel.sendBytes(first, 10, 20)
            Triple(header, first, end)
        }
        client().use { socket ->
            val channel = SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(), key, "pairing")
            channel.send(JSONObject().put("name", "a.bin"))
            channel.sendBytes(chunk)
            channel.sendBytes(ByteArray(0))
            val answer = channel.receive()
            assertEquals(chunk.size, answer.getInt("size"))
            assertEquals(0, answer.getInt("end"))
            assertTrue(chunk.copyOfRange(10, 30).contentEquals(channel.receiveBytes()))
        }
        val (header, first, end) = served.result()
        assertEquals("a.bin", header)
        assertTrue(chunk.contentEquals(first))
        assertEquals(0, end.size)
    }

    @Test
    fun bytesOutOfTheirPlaceAreNotAccepted() {
        //a frame sent twice has the number of an earlier one
        val served = serve { socket ->
            val channel = SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { key }.channel
            channel.receiveBytes()
            codeOf { channel.receiveBytes() }
        }
        val captured = java.io.ByteArrayOutputStream()
        client().use { socket ->
            val output = object : java.io.OutputStream() {
                override fun write(b: Int) {
                    captured.write(b)
                    socket.getOutputStream().write(b)
                }

                override fun flush() = socket.getOutputStream().flush()
            }
            val channel = SyncChannel.connect(socket.getInputStream(), output, key, "pairing")
            captured.reset()
            channel.sendBytes(byteArrayOf(1, 2, 3))
            socket.getOutputStream().write(captured.toByteArray())
            assertEquals(SyncChannel.BAD_KEY, served.result())
        }
    }

    @Test
    fun namesOfFilesAreMadeSafe() {
        assertEquals("photo.jpg", SyncRequests.safeFileName("photo.jpg"))
        assertEquals("passwd", SyncRequests.safeFileName("../../etc/passwd"))
        assertEquals("evil.apk", SyncRequests.safeFileName("..\\..\\evil.apk"))
        assertEquals("file", SyncRequests.safeFileName(".."))
        assertEquals("file", SyncRequests.safeFileName(""))
        assertEquals("file", SyncRequests.safeFileName("dir/"))
        assertEquals("hidden", SyncRequests.safeFileName(".hidden"))
        assertEquals("ab.txt", SyncRequests.safeFileName("a\u0000\nb.txt"))
        assertEquals("what.txt", SyncRequests.safeFileName("what?.txt"))
        assertEquals("Фото 1.jpg", SyncRequests.safeFileName("Фото 1.jpg"))
        //long names keep their extension and fit in 255 bytes of the file systems
        val long = SyncRequests.safeFileName("я".repeat(300) + ".mp4")
        assertTrue(long.endsWith("я.mp4"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= SyncRequests.MAX_FILE_NAME_BYTES)
        val emoji = SyncRequests.safeFileName("😀".repeat(100))
        assertEquals(0, emoji.length % 2)
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= SyncRequests.MAX_FILE_NAME_BYTES)
    }

    @Test
    fun typesAndUrlsOfFiles() {
        assertEquals("image/jpeg", SyncRequests.safeMimeType("Image/JPEG"))
        assertEquals("application/vnd.android.package-archive",
            SyncRequests.safeMimeType("application/vnd.android.package-archive"))
        assertNull(SyncRequests.safeMimeType(""))
        assertNull(SyncRequests.safeMimeType("text/html; charset=utf-8"))
        assertNull(SyncRequests.safeMimeType(null))
        val url = SyncRequests.phoneFileUrl("Pixel 8 & Co")
        assertFalse(url.startsWith("http"))
        assertEquals("Pixel 8 & Co", SyncRequests.phoneOfFileUrl(url))
        assertNull(SyncRequests.phoneOfFileUrl("https://example.com/a.jpg"))
    }

    @Test
    fun aClientWithAnotherKeyIsNotUnderstood() {
        val served = serve { socket ->
            val accepted = SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { key }
            codeOf { accepted.channel.receive() }
        }
        client().use { socket ->
            val channel = SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(),
                SyncCrypto.randomBytes(SyncCrypto.KEY_SIZE), "pairing")
            channel.send(JSONObject().put("text", "x"))
            assertEquals(SyncChannel.BAD_KEY, served.result())
        }
    }

    @Test
    fun anUnknownPairingIsRefused() {
        val served = serve { socket ->
            codeOf { SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { null } }
        }
        client().use { socket ->
            assertEquals(SyncChannel.UNKNOWN_PAIRING,
                codeOf { SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(), key, "pairing") })
        }
        assertEquals(SyncChannel.UNKNOWN_PAIRING, served.result())
    }

    @Test
    fun pairingWithoutAQrCodeIsRefused() {
        serve { socket -> codeOf { SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { null } } }
        client().use { socket ->
            assertEquals(SyncChannel.PAIRING_CLOSED,
                codeOf { SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(), key, null) })
        }
    }

    @Test
    fun aHugeFrameIsNotRead() {
        val served = serve { socket ->
            codeOf { SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { key } }
        }
        client().use { socket ->
            DataOutputStream(socket.getOutputStream()).apply {
                //the handshake allows much less than a frame of the session
                writeInt(64 * 1024)
                flush()
            }
            assertEquals(SyncChannel.BAD_MESSAGE, served.result())
        }
    }

    @Test
    fun sessionsOfTheSameKeyHaveTheirOwnKeys() {
        //a frame of one session is not accepted in another one
        val first = serve { socket ->
            SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { key }.channel.receive()
        }
        val captured = java.io.ByteArrayOutputStream()
        client().use { socket ->
            val output = object : java.io.OutputStream() {
                override fun write(b: Int) {
                    captured.write(b)
                    socket.getOutputStream().write(b)
                }

                override fun flush() = socket.getOutputStream().flush()
            }
            val channel = SyncChannel.connect(socket.getInputStream(), output, key, "pairing")
            captured.reset()
            channel.send(JSONObject().put("text", "first"))
            first.result()
        }
        val second = serve { socket ->
            val accepted = SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { key }
            codeOf { accepted.channel.receive() }
        }
        client().use { socket ->
            SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(), key, "pairing")
            socket.getOutputStream().write(captured.toByteArray())
            assertEquals(SyncChannel.BAD_KEY, second.result())
        }
    }

    @Test
    fun linkGoesThroughItsUrl() {
        val link = SyncLink(listOf("192.168.0.120", "10.0.0.2"), 41873, key, "tv-id", "Living room TV & more")
        val url = link.toUrl()
        assertTrue(url.startsWith("https://tvbro.phlox.dev/sync#"))
        assertEquals(link, SyncLink.parse(url))
        assertTrue(SyncLink.isSyncUrl(url))
        assertTrue(SyncLink.isSyncUrl("https://tvbro.phlox.dev/sync/"))
        assertFalse(SyncLink.isSyncUrl("https://tvbro.phlox.dev/synchronize"))
        //the page of the site opens it in the app with its own scheme
        assertEquals(link, SyncLink.parse(url.replace(SyncLink.URL_PREFIX, SyncLink.APP_URL_PREFIX)))
        assertFalse(SyncLink.isSyncUrl("tvbro://other#v=1"))
    }

    @Test
    fun brokenLinksAreNotParsed() {
        val url = SyncLink(listOf("192.168.0.120"), 41873, key, "tv-id", "TV").toUrl()
        assertNull(SyncLink.parse(url.replace("v=1", "v=2")))
        assertNull(SyncLink.parse(url.replace("p=41873", "p=70000")))
        assertNull(SyncLink.parse(url.substringBefore('#')))
        assertNull(SyncLink.parse(url.replace("https://tvbro.phlox.dev/sync", "https://example.com/sync")))
        assertNull(SyncLink.parse(url.replace(Regex("k=[^&]+"), "k=short")))
    }

    @Test
    fun onlyWebPagesAreAllowed() {
        assertTrue(SyncRequests.isAllowedUrl("https://example.com/"))
        assertTrue(SyncRequests.isAllowedUrl("HTTP://example.com"))
        assertFalse(SyncRequests.isAllowedUrl("javascript:alert(1)"))
        assertFalse(SyncRequests.isAllowedUrl("file:///sdcard/a.html"))
        assertFalse(SyncRequests.isAllowedUrl("intent://x#Intent;end"))
        assertFalse(SyncRequests.isAllowedUrl("about:home"))
        assertFalse(SyncRequests.isAllowedUrl("https://"))
        assertFalse(SyncRequests.isAllowedUrl("https://example.com/" + "a".repeat(SyncRequests.MAX_URL_LENGTH)))
    }

    @Test
    fun hkdfMatchesRfc5869() {
        //test case 1 of RFC 5869 (the first 32 bytes of its output)
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = String(ByteArray(10) { (0xf0 + it).toByte() }, Charsets.ISO_8859_1)
        //the info of the test is not UTF-8: compare through the bytes of the expand step
        val prk = SyncCrypto.hmac(salt, ikm)
        val okm = SyncCrypto.hmac(prk, info.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1))
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
            okm.joinToString("") { "%02x".format(it) })
        assertEquals(32, SyncCrypto.hkdf(ikm, salt, "x").size)
        if (SyncCrypto.hkdf(ikm, salt, "a").contentEquals(SyncCrypto.hkdf(ikm, salt, "b"))) fail()
    }
}
