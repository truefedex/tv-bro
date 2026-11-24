package com.phlox.tvwebbrowser.adblock.sitefilter

import com.phlox.tvwebbrowser.adblock.AdblockFormatException
import com.phlox.tvwebbrowser.adblock.engine.EngineFile
import com.phlox.tvwebbrowser.adblock.engine.Hashing
import java.io.File
import java.io.IOException

/**
 * Lists of sites by category (adult, gambling…): each a sorted array of the 64-bit hashes of its domains
 * ([Hashing.domain]). A domain of a list stands for itself and its subdomains, so a host is in a list when it or a
 * domain above it is. The app knows which category an index is: the filter only keeps them in their order.
 * Immutable and thread-safe; [match] allocates nothing.
 */
class SiteFilter internal constructor(
    /** Of the lists it was built of, as the app tells it */
    val fingerprint: Long,
    private val categories: Array<LongArray>,
    /** The categories built from a list (a mask of their bits): a list may give no domains */
    val present: Long,
) {
    val categoryCount: Int get() = categories.size

    /** The domains of the list of the [category] */
    fun size(category: Int): Int = categories.getOrNull(category)?.size ?: 0

    val totalSize: Int get() = categories.sumOf { it.size }

    /** The [category] was built from a list */
    fun has(category: Int): Boolean = present and (1L shl category) != 0L

    internal fun hashesOf(category: Int): LongArray? = categories.getOrNull(category)

    /**
     * The first category of the [enabled] ones (a mask of their bits, `1 shl index`) whose list has [host] or a
     * domain above it; -1 if none. [host] as in an url: case and a trailing dot do not matter.
     */
    fun match(host: String, enabled: Long): Int {
        if (enabled == 0L || host.isEmpty()) return -1
        val name = Hashing.asciiLowercase(host)
        val end = if (name.endsWith('.')) name.length - 1 else name.length
        if (end <= 0) return -1
        var start = 0
        while (start < end) {
            val hash = Hashing.domain(name, start, end)
            for (i in categories.indices) {
                if (enabled and (1L shl i) == 0L) continue
                if (categories[i].binarySearch(hash) >= 0) return i
            }
            val dot = name.indexOf('.', start)
            if (dot < 0 || dot >= end) break
            start = dot + 1
        }
        return -1
    }

    /** Written atomically (see [EngineFile]) */
    @Throws(IOException::class)
    fun writeTo(file: File, createdAt: Long = System.currentTimeMillis()) {
        val sections = HashMap<Int, Any>()
        sections[INFO] = intArrayOf(VERSION, categories.size, present.toInt(), (present ushr 32).toInt())
        categories.forEachIndexed { i, hashes -> sections[CATEGORY + i] = hashes }
        EngineFile.write(file, fingerprint, createdAt, sections)
    }

    companion object {
        /** Raise it whenever what is written or how lines are read changes: older files are built again */
        const val VERSION = 1
        /** No more categories: they are bits of a mask */
        const val MAX_CATEGORIES = 63
        private const val INFO = 1
        private const val CATEGORY = 10

        @Throws(IOException::class, AdblockFormatException::class)
        fun load(file: File): SiteFilter {
            val content = EngineFile.read(file.readBytes())
            val info = content.sections[INFO] as? IntArray ?: throw AdblockFormatException("no info section")
            if (info.size < 4 || info[0] != VERSION) throw AdblockFormatException("site filter version ${info.getOrNull(0)}")
            val categories = Array(info[1]) { i ->
                content.sections[CATEGORY + i] as? LongArray ?: throw AdblockFormatException("no category $i")
            }
            val present = (info[2].toLong() and 0xffffffffL) or (info[3].toLong() shl 32)
            return SiteFilter(content.fingerprint, categories, present)
        }
    }
}

/**
 * Builds a [SiteFilter] from the lines of lists, each list of a category (its index). From a [base] filter, the
 * categories not added or cleared stay as they are there: a list downloaded again replaces only its own category, so
 * the lists need not be kept to build the filter again.
 */
class SiteFilterCompiler(private val categoryCount: Int, private val base: SiteFilter? = null) {
    private val hashes = arrayOfNulls<HashSetOfLongs>(categoryCount)
    private var cleared = 0L

    init {
        require(categoryCount in 0..SiteFilter.MAX_CATEGORIES) { "categories: $categoryCount" }
    }

    /** Adds the domains of the [lines] of a list to the [category]; @return how many lines were domains */
    fun add(category: Int, lines: Sequence<String>): Int {
        val set = hashes[category] ?: HashSetOfLongs().also { hashes[category] = it }
        cleared = cleared and (1L shl category).inv()
        var count = 0
        for (line in lines) {
            val domain = DomainListParser.parseLine(line) ?: continue
            set.add(Hashing.domain(domain))
            count++
        }
        return count
    }

    /** The [category] has no list any more (not even that of the [base]) */
    fun clear(category: Int) {
        hashes[category] = null
        cleared = cleared or (1L shl category)
    }

    fun build(fingerprint: Long): SiteFilter {
        var present = 0L
        val arrays = Array(categoryCount) { i ->
            val bit = 1L shl i
            val added = hashes[i]
            when {
                added != null -> {
                    present = present or bit
                    added.toSortedArray()
                }
                cleared and bit != 0L -> LongArray(0)
                base != null && base.has(i) -> {
                    present = present or bit
                    base.hashesOf(i) ?: LongArray(0)
                }
                else -> LongArray(0)
            }
        }
        return SiteFilter(fingerprint, arrays, present)
    }

    /** Grows as lists come, without a boxed Long per domain (lists have hundreds of thousands) */
    private class HashSetOfLongs {
        private var values = LongArray(1024)
        private var size = 0

        fun add(value: Long) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toSortedArray(): LongArray {
            val sorted = values.copyOf(size)
            sorted.sort()
            //one list may have a domain twice, two lists of a category surely do
            var unique = 0
            for (i in sorted.indices) {
                if (unique == 0 || sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
            }
            return sorted.copyOf(unique)
        }
    }
}

/**
 * Reads the lines of lists of sites: a domain per line, plain (`example.com`), as a wildcard (`*.example.com`),
 * in a hosts file (`0.0.0.0 example.com`) or as a filter of a whole host (`||example.com^`). Comments (`#`, `!`),
 * IP addresses and anything else are skipped.
 */
object DomainListParser {
    /** The domain of the [line] in lowercase, without a trailing dot; null if the line is not one */
    fun parseLine(line: String): String? {
        var text = line.trim()
        if (text.isEmpty() || text[0] == '#' || text[0] == '!' || text[0] == '[') return null
        //a comment after the entry
        text.indexOf('#').let { if (it > 0) text = text.substring(0, it).trim() }
        if (text.startsWith("||")) {
            if (!text.endsWith('^')) return null
            text = text.substring(2, text.length - 1)
        } else {
            val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
            text = when {
                parts.size == 1 -> parts[0]
                parts.size >= 2 && isIpAddress(parts[0]) -> parts[1]
                else -> return null
            }
        }
        if (text.startsWith("*.")) text = text.substring(2)
        text = text.trimStart('.').trimEnd('.')
        if (text.isEmpty() || isIpAddress(text)) return null
        text = Hashing.asciiLowercase(text)
        for (c in text) {
            if (c !in 'a'..'z' && c !in '0'..'9' && c != '.' && c != '-' && c != '_') return null
        }
        //hosts files name the machine itself
        if (text == "localhost" || text == "localhost.localdomain" || text == "broadcasthost") return null
        return text
    }

    private fun isIpAddress(text: String): Boolean =
        text.contains(':') || text.all { it in '0'..'9' || it == '.' }
}
