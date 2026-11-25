package com.phlox.tvwebbrowser.net.dns

import com.phlox.tvwebbrowser.net.DnsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class DohResolverTest {
    private var now = 0L
    private val asked = ArrayList<Pair<String, Int>>()
    private val systemAsked = ArrayList<String>()
    /** The records of the server by name and type; a name it does not know is not there */
    private val records = HashMap<Pair<String, Int>, List<ByteArray>>()
    private var ttl = 300
    private var serverDown = false

    private val resolver = DohResolver(
        transport = { query -> exchange(query) },
        executor = Executor { it.run() },
        systemResolver = { name -> systemAsked += name; listOf(InetAddress.getByName("192.0.2.1")) },
        nanoTime = { now }
    )

    @Test
    fun ipv4AddressesComeFirst() {
        records["example.com" to DnsMessage.TYPE_AAAA] = listOf(ByteArray(16) { 0x20 })
        records["example.com" to DnsMessage.TYPE_A] = listOf(byteArrayOf(93, 1, 2, 3))
        val addresses = resolver.resolve("Example.com.")
        assertEquals(listOf(InetAddress.getByName("93.1.2.3"), InetAddress.getByAddress(ByteArray(16) { 0x20 })), addresses)
        assertTrue(systemAsked.isEmpty())
    }

    @Test
    fun answersAreKeptForTheirTimeToLive() {
        records["example.com" to DnsMessage.TYPE_A] = listOf(byteArrayOf(93, 1, 2, 3))
        ttl = 120
        resolver.resolve("example.com")
        resolver.resolve("example.com")
        assertEquals(2, asked.size)
        now += 119_000_000_000L
        resolver.resolve("example.com")
        assertEquals(2, asked.size)
        now += 2_000_000_000L
        resolver.resolve("example.com")
        assertEquals(4, asked.size)
    }

    @Test
    fun shortTimesToLiveAreKeptAMinute() {
        records["example.com" to DnsMessage.TYPE_A] = listOf(byteArrayOf(93, 1, 2, 3))
        ttl = 5
        resolver.resolve("example.com")
        now += 59_000_000_000L
        resolver.resolve("example.com")
        assertEquals(2, asked.size)
    }

    @Test
    fun aNameTheServerDoesNotKnowIsNotAskedOfTheDevice() {
        try {
            resolver.resolve("blocked.example")
            fail()
        } catch (e: UnknownHostException) {
            //expected
        }
        assertTrue(systemAsked.isEmpty())
        //kept too
        runCatching { resolver.resolve("blocked.example") }
        assertEquals(2, asked.size)
    }

    @Test
    fun theDeviceAnswersWhileTheServerDoesNot() {
        serverDown = true
        assertEquals(listOf(InetAddress.getByName("192.0.2.1")), resolver.resolve("example.com"))
        assertEquals(listOf("example.com"), systemAsked)
        //the server is not asked again for a while
        resolver.resolve("example.org")
        assertEquals(2, asked.size)
        assertEquals(2, systemAsked.size)
        //then it is
        serverDown = false
        records["example.net" to DnsMessage.TYPE_A] = listOf(byteArrayOf(93, 1, 2, 3))
        now += DohResolver.DOWN_SECONDS * 1_000_000_000L + 1
        assertEquals(listOf(InetAddress.getByName("93.1.2.3")), resolver.resolve("example.net"))
        assertEquals(2, systemAsked.size)
    }

    @Test
    fun serversOfProvidersAreAskedAtTheirAddress() {
        val google = DnsSettings.of(DnsSettings.Provider.GOOGLE, "")!!
        assertEquals("https://8.8.8.8/dns-query", DohResolver.urlOf(google).toString())
        assertEquals("https://dns.example.com:8443/q?x=1",
            DohResolver.urlOf(DnsSettings("https://dns.example.com:8443/q?x=1", null)).toString())
        assertTrue(DohResolver.transportOf(google) is DohResolver.HttpsTransport)
        //Quad9 answers HTTPS only over HTTP/2
        assertTrue(DohResolver.transportOf(DnsSettings.of(DnsSettings.Provider.QUAD9, "")!!) is DotTransport)
    }

    private fun exchange(query: ByteArray): ByteArray {
        val name = nameOf(query)
        val type = ((query[query.size - 4].toInt() and 0xFF) shl 8) or (query[query.size - 3].toInt() and 0xFF)
        asked += name to type
        if (serverDown) throw IOException("DNS server answered 503")
        val known = records.keys.any { it.first == name }
        val data = records[name to type].orEmpty()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x81.toByte(), (0x80 or if (known) 0 else DnsMessage.RCODE_NAME_ERROR).toByte(),
            0, 1, 0, data.size.toByte(), 0, 0, 0, 0))
        out.write(query, 12, query.size - 12)
        for (rdata in data) {
            out.write(byteArrayOf(0xC0.toByte(), 12, (type shr 8).toByte(), type.toByte(), 0, 1,
                (ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte(), 0, rdata.size.toByte()))
            out.write(rdata)
        }
        return out.toByteArray()
    }

    private fun nameOf(query: ByteArray): String {
        val labels = ArrayList<String>()
        var i = 12
        while (query[i].toInt() != 0) {
            val length = query[i].toInt()
            labels += String(query, i + 1, length, Charsets.US_ASCII)
            i += length + 1
        }
        return labels.joinToString(".")
    }
}
