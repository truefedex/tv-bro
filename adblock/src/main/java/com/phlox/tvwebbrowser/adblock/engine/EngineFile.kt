package com.phlox.tvwebbrowser.adblock.engine

import com.phlox.tvwebbrowser.adblock.AdblockFormatException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * The file of a compiled engine: a header, then sections of primitive arrays, little-endian.
 *
 * Header (32 bytes): magic `TVBA`, format version (int), fingerprint of the lists (long), creation time
 * (long), length of the body (int), CRC32 of the body (int).
 * A section: id, kind (1 bytes, 2 ints, 3 longs), number of elements, a zero int, then the elements, padded
 * to 8 bytes.
 */
internal object EngineFile {
    /** Raise it whenever what is written changes: older files are compiled again from the lists */
    const val FORMAT_VERSION = 1
    private const val MAGIC = 0x41425654 // "TVBA" in little-endian bytes
    private const val HEADER_SIZE = 32
    private const val KIND_BYTES = 1
    private const val KIND_INTS = 2
    private const val KIND_LONGS = 3

    const val FLAGS = 1
    const val PATTERN_OFFSETS = 2
    const val PATTERN_LENGTHS = 3
    const val DOMAIN_REFS = 4
    const val POOL = 5
    const val DOMAIN_LISTS = 6
    const val DOMAIN_HASHES = 7
    const val REGEX_IDS = 8
    const val REGEX_LITERALS = 9
    /** Small numbers: the count of page blocking filters */
    const val INFO = 10
    /** The arrays of an [IndexTable] ([IndexTable.arrays]) */
    const val TABLE_ARRAYS = 7

    fun tableSection(table: Int, array: Int) = 100 + table * 10 + array

    class Content(val fingerprint: Long, val createdAt: Long, val sections: Map<Int, Any>)

    fun write(file: File, fingerprint: Long, createdAt: Long, sections: Map<Int, Any>) {
        var bodySize = 0L
        for (array in sections.values) bodySize += 16 + padded(byteSize(array))
        require(bodySize < Int.MAX_VALUE) { "engine too large" }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + bodySize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(HEADER_SIZE)
        for ((id, array) in sections.toSortedMap()) {
            buffer.putInt(id)
            when (array) {
                is ByteArray -> {
                    buffer.putInt(KIND_BYTES).putInt(array.size).putInt(0)
                    buffer.put(array)
                }
                is IntArray -> {
                    buffer.putInt(KIND_INTS).putInt(array.size).putInt(0)
                    buffer.asIntBuffer().put(array)
                    buffer.position(buffer.position() + array.size * 4)
                }
                is LongArray -> {
                    buffer.putInt(KIND_LONGS).putInt(array.size).putInt(0)
                    buffer.asLongBuffer().put(array)
                    buffer.position(buffer.position() + array.size * 8)
                }
                else -> throw IllegalArgumentException("section $id: ${array.javaClass}")
            }
            while (buffer.position() % 8 != 0) buffer.put(0)
        }
        val bytes = buffer.array()
        val crc = CRC32()
        crc.update(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        buffer.position(0)
        buffer.putInt(MAGIC).putInt(FORMAT_VERSION).putLong(fingerprint).putLong(createdAt)
            .putInt(bytes.size - HEADER_SIZE).putInt(crc.value.toInt())

        //a process killed while writing leaves the previous file as it was
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            //renameTo does not replace on some platforms (Windows); Android's rename does
            file.delete()
            if (!temp.renameTo(file)) throw IOException("can not rename $temp to $file")
        }
    }

    fun read(bytes: ByteArray): Content {
        if (bytes.size < HEADER_SIZE) throw AdblockFormatException("truncated header")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt() != MAGIC) throw AdblockFormatException("not an engine file")
        val version = buffer.getInt()
        if (version != FORMAT_VERSION) throw AdblockFormatException("format version $version, not $FORMAT_VERSION")
        val fingerprint = buffer.getLong()
        val createdAt = buffer.getLong()
        val bodyLength = buffer.getInt()
        val expectedCrc = buffer.getInt()
        if (bodyLength != bytes.size - HEADER_SIZE) throw AdblockFormatException("truncated or extended body")
        val crc = CRC32()
        crc.update(bytes, HEADER_SIZE, bodyLength)
        if (crc.value.toInt() != expectedCrc) throw AdblockFormatException("damaged body (CRC)")

        val sections = HashMap<Int, Any>()
        while (buffer.remaining() > 0) {
            if (buffer.remaining() < 16) throw AdblockFormatException("truncated section header")
            val id = buffer.getInt()
            val kind = buffer.getInt()
            val count = buffer.getInt()
            buffer.getInt()
            val elementSize = when (kind) {
                KIND_BYTES -> 1
                KIND_INTS -> 4
                KIND_LONGS -> 8
                else -> throw AdblockFormatException("section $id of kind $kind")
            }
            if (count < 0 || count.toLong() * elementSize > buffer.remaining()) throw AdblockFormatException("section $id size")
            //bulk copies: a load runs once, mostly in the interpreter of ART, where loops are slow
            val position = buffer.position()
            sections[id] = when (kind) {
                KIND_BYTES -> bytes.copyOfRange(position, position + count)
                KIND_INTS -> IntArray(count).also { buffer.asIntBuffer().get(it) }
                else -> LongArray(count).also { buffer.asLongBuffer().get(it) }
            }
            buffer.position(position + count * elementSize)
            val padding = (8 - buffer.position() % 8) % 8
            if (padding > buffer.remaining()) throw AdblockFormatException("section $id padding")
            buffer.position(buffer.position() + padding)
        }
        return Content(fingerprint, createdAt, sections)
    }

    private fun byteSize(array: Any): Long = when (array) {
        is ByteArray -> array.size.toLong()
        is IntArray -> array.size * 4L
        is LongArray -> array.size * 8L
        else -> throw IllegalArgumentException(array.javaClass.toString())
    }

    private fun padded(size: Long) = (size + 7) / 8 * 8
}
