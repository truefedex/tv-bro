package com.phlox.tvwebbrowser.service.downloads

import com.phlox.tvwebbrowser.model.Download
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class HttpDownloadsTest {
    private val file = ByteArray(1000) { (it % 251).toByte() }
    private lateinit var server: HttpServer
    private var etag = "\"v1\""
    private var ranges = true
    /** The headers of the last request to the file */
    private val requests = ArrayList<Map<String, String?>>()

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.bin") { serveFile(it) }
        server.createContext("/moved") { exchange ->
            requests += mapOf("Cookie" to exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/file.bin")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun serveFile(exchange: HttpExchange) {
        val headers = exchange.requestHeaders
        requests += mapOf("Range" to headers.getFirst("Range"), "If-Range" to headers.getFirst("If-Range"),
            "Cookie" to headers.getFirst("Cookie"), "Accept-Encoding" to headers.getFirst("Accept-Encoding"))
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.responseHeaders.add("ETag", etag)
        if (ranges) exchange.responseHeaders.add("Accept-Ranges", "bytes")
        val range = headers.getFirst("Range")
        val ifRange = headers.getFirst("If-Range")
        if (ranges && range != null && (ifRange == null || ifRange == etag)) {
            val start = range.removePrefix("bytes=").substringBefore('-').toInt()
            if (start >= file.size) {
                exchange.responseHeaders.add("Content-Range", "bytes */${file.size}")
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return
            }
            exchange.responseHeaders.add("Content-Range", "bytes $start-${file.size - 1}/${file.size}")
            exchange.sendResponseHeaders(206, (file.size - start).toLong())
            exchange.responseBody.use { it.write(file, start, file.size - start) }
            return
        }
        exchange.sendResponseHeaders(200, file.size.toLong())
        exchange.responseBody.use { it.write(file) }
    }

    private fun download(path: String = "/file.bin") = Download().apply {
        url = base + path
        cookies = "sid=1"
    }

    @Test
    fun rangeDecisions() {
        assertEquals(RangeDecision.APPEND, decideRange(206, 100, 100, 1000))
        assertEquals(RangeDecision.RESTART, decideRange(206, 100, 0, 1000))
        assertEquals(RangeDecision.RESTART, decideRange(200, 100, null, 1000))
        assertEquals(RangeDecision.COMPLETE, decideRange(416, 1000, null, 1000))
        assertEquals(RangeDecision.RESTART, decideRange(416, 500, null, 1000))
        assertEquals(RangeDecision.RESTART, decideRange(416, 500, null, 0))
        assertEquals(RangeDecision.FAIL, decideRange(403, 100, null, 1000))
        assertEquals(100L, contentRangeStart("bytes 100-999/1000"))
        assertEquals(1000L, contentRangeSize("bytes 100-999/1000"))
        assertNull(contentRangeSize("bytes 100-999/*"))
        assertNull(contentRangeStart(null))
    }

    @Test
    fun theWholeFileTellsWhetherItCanBeResumed() {
        val download = download()
        val response = HttpDownloads.open(download, 0)
        assertArrayEquals(file, response.input.use { it.readBytes() })
        assertEquals(0L, response.start)
        assertEquals(1000L, download.size)
        assertEquals("\"v1\"", download.etag)
        assertTrue(download.resumable)
        assertEquals("identity", requests.last()["Accept-Encoding"])
        assertNull(requests.last()["Range"])

        ranges = false
        val other = download()
        HttpDownloads.open(other, 0).input.close()
        assertFalse(other.resumable)
    }

    @Test
    fun theRestOfTheFileIsAppended() {
        val download = download()
        HttpDownloads.open(download, 0).input.close()
        val response = HttpDownloads.open(download, 600)
        assertEquals(600L, response.start)
        assertArrayEquals(file.copyOfRange(600, 1000), response.input.use { it.readBytes() })
        assertEquals("bytes=600-", requests.last()["Range"])
        assertEquals("\"v1\"", requests.last()["If-Range"])
        assertEquals(1000L, download.size)
    }

    @Test
    fun aChangedFileOrAServerWithoutRangesStartsAgain() {
        val download = download()
        HttpDownloads.open(download, 0).input.close()
        etag = "\"v2\""
        val response = HttpDownloads.open(download, 600)
        assertEquals(0L, response.start)
        assertArrayEquals(file, response.input.use { it.readBytes() })
        assertEquals("\"v2\"", download.etag)

        ranges = false
        val again = HttpDownloads.open(download, 600)
        assertEquals(0L, again.start)
        again.input.close()
    }

    @Test
    fun aCompleteFileIsComplete() {
        val download = download()
        HttpDownloads.open(download, 0).input.close()
        val response = HttpDownloads.open(download, 1000)
        assertEquals(1000L, response.start)
        assertEquals(-1, response.input.read())
    }

    @Test
    fun redirectsAreFollowedWithoutTheCookiesOfTheSite() {
        val download = download("/moved")
        val response = HttpDownloads.open(download, 0)
        assertArrayEquals(file, response.input.use { it.readBytes() })
        //127.0.0.1 redirected to localhost: another host
        assertEquals("sid=1", requests[0]["Cookie"])
        assertNull(requests[1]["Cookie"])
    }

    @Test
    fun errorsFail() {
        try {
            HttpDownloads.open(download("/missing"), 0)
            fail()
        } catch (e: HttpException) {
            assertEquals(404, e.code)
        }
    }
}
