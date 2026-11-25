package com.phlox.tvwebbrowser.net.dns

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress

/** The wire format of DNS (RFC 1035) for questions of one name and their answers, as DNS over HTTPS sends them */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    private const val HEADER_SIZE = 12
    private const val MAX_NAME_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    const val RCODE_OK = 0
    const val RCODE_NAME_ERROR = 3

    /** The addresses of an answer and how long they may be kept */
    class Answer(val rcode: Int, val addresses: List<InetAddress>, val ttlSeconds: Long)

    /**
     * The question of the addresses of [type] of [name] (an ASCII name; recursion desired). Its id is 0, as RFC 8484
     * asks, so that HTTP caches can keep it.
     */
    fun query(name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream(HEADER_SIZE + name.length + 6)
        //id 0, flags: RD; one question
        out.write(byteArrayOf(0, 0, 0x01, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeName(out, name)
        out.write(type shr 8)
        out.write(type and 0xFF)
        out.write(0)
        out.write(CLASS_IN)
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        val trimmed = name.trimEnd('.')
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_NAME_LENGTH) { "Bad name: $name" }
        for (label in trimmed.split('.')) {
            val bytes = label.lowercase().toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= MAX_LABEL_LENGTH && label.all { it.code < 128 }) {
                "Bad name: $name"
            }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    /**
     * The addresses of [type] that [message] answers (those of the name it was asked about and of the names it
     * points to); the shortest time to live of them
     */
    @Throws(IOException::class)
    fun parse(message: ByteArray, type: Int): Answer {
        val reader = Reader(message)
        reader.skip(2)
        val flags = reader.u16()
        if (flags and 0x8000 == 0) throw IOException("Not an answer")
        val rcode = flags and 0x0F
        val questions = reader.u16()
        val answers = reader.u16()
        reader.skip(4)
        repeat(questions) {
            reader.skipName()
            reader.skip(4)
        }
        val addresses = ArrayList<InetAddress>()
        var ttl = Long.MAX_VALUE
        repeat(answers) {
            reader.skipName()
            val recordType = reader.u16()
            val recordClass = reader.u16()
            val recordTtl = reader.u32()
            val length = reader.u16()
            val size = if (type == TYPE_A) 4 else 16
            if (recordType == type && recordClass == CLASS_IN && length == size) {
                addresses += InetAddress.getByAddress(reader.bytes(length))
                ttl = minOf(ttl, recordTtl)
            } else {
                reader.skip(length)
            }
        }
        return Answer(rcode, addresses, if (addresses.isEmpty()) 0 else ttl)
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        fun skip(count: Int) {
            if (count < 0 || position + count > bytes.size) throw IOException("Truncated DNS message")
            position += count
        }

        fun u8(): Int {
            if (position >= bytes.size) throw IOException("Truncated DNS message")
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun bytes(count: Int): ByteArray {
            val start = position
            skip(count)
            return bytes.copyOfRange(start, start + count)
        }

        /** A name: labels ending with an empty one or a pointer (compression) */
        fun skipName() {
            while (true) {
                val length = u8()
                when {
                    length == 0 -> return
                    length and 0xC0 == 0xC0 -> {
                        skip(1)
                        return
                    }
                    length and 0xC0 != 0 -> throw IOException("Bad label in DNS message")
                    else -> skip(length)
                }
            }
        }
    }
}
