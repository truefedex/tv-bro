package com.phlox.tvwebbrowser.net.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress

class DnsMessageTest {
    @Test
    fun queryIsTheWireFormatOfRfc8484() {
        //the example of RFC 8484, 4.1.1 (www.example.com, A)
        val expected = java.util.Base64.getUrlDecoder().decode("AAABAAABAAAAAAAAA3d3dwdleGFtcGxlA2NvbQAAAQAB")
        assertArrayEquals(expected, DnsMessage.query("www.example.com", DnsMessage.TYPE_A))
        assertArrayEquals(expected, DnsMessage.query("WWW.Example.com.", DnsMessage.TYPE_A))
    }

    @Test(expected = IllegalArgumentException::class)
    fun namesOutsideAsciiAreRefused() {
        DnsMessage.query("пример.рф", DnsMessage.TYPE_A)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyLabelsAreRefused() {
        DnsMessage.query("www..example.com", DnsMessage.TYPE_A)
    }

    @Test
    fun answersGiveTheAddressesOfTheirTypeAndTheShortestTtl() {
        //www.example.com CNAME example.com (pointer), then two A records and an AAAA one
        val message = answer(
            record(name(0xC0, 12), TYPE_CNAME, 300, byteArrayOf(7) + "example".toByteArray() + byteArrayOf(0xC0.toByte(), 16)),
            record(name(0xC0, 12), DnsMessage.TYPE_A, 120, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)),
            record(name(0xC0, 12), DnsMessage.TYPE_A, 90, byteArrayOf(93, 184.toByte(), 216.toByte(), 35)),
            record(name(0xC0, 12), DnsMessage.TYPE_AAAA, 30, ByteArray(16) { 1 })
        )
        val a = DnsMessage.parse(message, DnsMessage.TYPE_A)
        assertEquals(DnsMessage.RCODE_OK, a.rcode)
        assertEquals(listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("93.184.216.35")), a.addresses)
        assertEquals(90L, a.ttlSeconds)
        val aaaa = DnsMessage.parse(message, DnsMessage.TYPE_AAAA)
        assertEquals(1, aaaa.addresses.size)
        assertEquals(30L, aaaa.ttlSeconds)
    }

    @Test
    fun aNameThatIsNotThereHasNoAddresses() {
        val message = answer(rcode = DnsMessage.RCODE_NAME_ERROR)
        val answer = DnsMessage.parse(message, DnsMessage.TYPE_A)
        assertEquals(DnsMessage.RCODE_NAME_ERROR, answer.rcode)
        assertTrue(answer.addresses.isEmpty())
    }

    @Test(expected = IOException::class)
    fun aQuestionIsNoAnswer() {
        DnsMessage.parse(DnsMessage.query("example.com", DnsMessage.TYPE_A), DnsMessage.TYPE_A)
    }

    @Test(expected = IOException::class)
    fun truncatedAnswersFail() {
        val message = answer(record(name(0xC0, 12), DnsMessage.TYPE_A, 120, byteArrayOf(1, 2, 3, 4)))
        DnsMessage.parse(message.copyOf(message.size - 2), DnsMessage.TYPE_A)
    }

    private fun name(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun record(name: ByteArray, type: Int, ttl: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(name)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1,
            (ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte(),
            (data.size shr 8).toByte(), data.size.toByte()))
        out.write(data)
        return out.toByteArray()
    }

    /** An answer to the question of www.example.com with [records] */
    private fun answer(vararg records: ByteArray, rcode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x81.toByte(), (0x80 or rcode).toByte(), 0, 1, 0, records.size.toByte(), 0, 0, 0, 0))
        val question = DnsMessage.query("www.example.com", DnsMessage.TYPE_A)
        out.write(question, 12, question.size - 12)
        records.forEach { out.write(it) }
        return out.toByteArray()
    }

    private companion object {
        const val TYPE_CNAME = 5
    }
}
