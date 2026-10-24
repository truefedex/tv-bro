package com.phlox.tvwebbrowser.adblock.psl

import java.net.IDN
import java.util.Locale
import java.util.TreeSet

/**
 * The Public Suffix List (publicsuffix.org): which part of a host is a public suffix (`com`, `co.uk`,
 * `github.io`), and so which is the registrable domain, the site (`example.co.uk` of `www.example.co.uk`).
 * `third-party` filters compare the sites of a request and of its page.
 *
 * It is loaded from the compact file [PublicSuffixListCompiler] makes (the asset `adblock/psl.txt`): the
 * bytes of the file stay as they are and hash tables of the offsets of its rules are built over them, no
 * object per rule. Lookups allocate nothing. Hosts are lowercase ASCII (punycode) without a trailing dot.
 * Thread-safe: it does not change after [load].
 */
class PublicSuffixList private constructor(
    /** The version of the list (its `VERSION` comment) */
    val version: String,
    private val exact: RuleTable,
    /** `*.ck`: the rule without `*.` */
    private val wildcard: RuleTable,
    /** `!www.ck`: the rule without `!` */
    private val exception: RuleTable,
) {
    val ruleCount: Int get() = exact.size + wildcard.size + exception.size

    /**
     * Where the public suffix of the [host] starts; 0 when the whole host is one, or it is an IP address.
     * A host under an unknown top-level domain has that domain as its public suffix.
     */
    fun publicSuffixStart(host: String): Int {
        if (isIpAddress(host)) return 0
        val end = host.length
        var start = 0
        while (true) {
            val next = host.indexOf('.', start)
            if (exception.contains(host, start, end)) return if (next < 0) start else next + 1
            if (exact.contains(host, start, end)) return start
            if (next < 0) return start
            if (wildcard.contains(host, next + 1, end)) return start
            start = next + 1
        }
    }

    /**
     * Where the registrable domain (the public suffix and one more label) of the [host] starts; 0 when the
     * host is a public suffix itself, an IP address or a single label.
     */
    fun registrableDomainStart(host: String): Int {
        val suffix = publicSuffixStart(host)
        if (suffix < 2) return 0
        //the label before the dot before the suffix
        return host.lastIndexOf('.', suffix - 2) + 1
    }

    fun registrableDomain(host: String): String = host.substring(registrableDomainStart(host))

    /** Whether the hosts belong to one site (the same registrable domain) */
    fun isSameSite(a: String, b: String): Boolean {
        val startA = registrableDomainStart(a)
        val startB = registrableDomainStart(b)
        val length = a.length - startA
        return length == b.length - startB && a.regionMatches(startA, b, startB, length)
    }

    private fun isIpAddress(host: String): Boolean {
        if (host.isEmpty() || host.contains(':')) return true
        return host.last() in '0'..'9' && host.all { it in '0'..'9' || it == '.' }
    }

    companion object {
        internal const val MAGIC = "PSL1"

        /** Loads the file [PublicSuffixListCompiler.compile] made; [IllegalArgumentException] if it is not one */
        fun load(data: ByteArray): PublicSuffixList {
            val reader = LineReader(data)
            val header = reader.next() ?: throw IllegalArgumentException("empty")
            require(header.startsWith(MAGIC)) { "not a compiled public suffix list" }
            val version = header.substring(MAGIC.length).trim()
            val exact = reader.table()
            val wildcard = reader.table()
            val exception = reader.table()
            return PublicSuffixList(version, exact, wildcard, exception)
        }
    }

    /** Reads the lines of the file: the header and the counts as strings, the rules as offsets */
    private class LineReader(private val data: ByteArray) {
        private var position = 0

        fun next(): String? {
            val end = lineEnd() ?: return null
            val line = String(data, position, contentEnd(end) - position, Charsets.US_ASCII)
            position = end + 1
            return line
        }

        fun table(): RuleTable {
            val count = next()?.toIntOrNull() ?: throw IllegalArgumentException("no rule count")
            val starts = IntArray(count)
            val ends = IntArray(count)
            for (i in 0 until count) {
                val end = lineEnd() ?: throw IllegalArgumentException("truncated")
                starts[i] = position
                ends[i] = contentEnd(end)
                position = end + 1
            }
            return RuleTable(data, starts, ends)
        }

        private fun lineEnd(): Int? {
            if (position >= data.size) return null
            var end = position
            while (end < data.size && data[end] != '\n'.code.toByte()) end++
            return end
        }

        /** The end of the line without a '\r' (a file whose line ends were converted) */
        private fun contentEnd(lineEnd: Int) =
            if (lineEnd > position && data[lineEnd - 1] == '\r'.code.toByte()) lineEnd - 1 else lineEnd
    }

    /** A hash set of the rules at [starts]..[ends] of [data], open addressing */
    private class RuleTable(private val data: ByteArray, private val starts: IntArray, private val ends: IntArray) {
        val size: Int get() = starts.size
        /** Index of the rule + 1 in each slot, 0 for an empty slot */
        private val slots = IntArray(Integer.highestOneBit(maxOf(4, starts.size) * 2) * 2)
        private val mask = slots.size - 1

        init {
            for (rule in starts.indices) {
                var slot = hashBytes(starts[rule], ends[rule]) and mask
                while (slots[slot] != 0) slot = (slot + 1) and mask
                slots[slot] = rule + 1
            }
        }

        fun contains(s: String, from: Int, to: Int): Boolean {
            var slot = hashChars(s, from, to) and mask
            while (true) {
                val rule = slots[slot] - 1
                if (rule < 0) return false
                if (equals(rule, s, from, to)) return true
                slot = (slot + 1) and mask
            }
        }

        private fun equals(rule: Int, s: String, from: Int, to: Int): Boolean {
            val start = starts[rule]
            if (ends[rule] - start != to - from) return false
            for (i in 0 until to - from) {
                if (data[start + i].toInt() and 0xFF != s[from + i].code) return false
            }
            return true
        }

        private fun hashBytes(from: Int, to: Int): Int {
            var h = FNV_OFFSET
            for (i in from until to) h = (h xor (data[i].toInt() and 0xFF)) * FNV_PRIME
            return mix(h)
        }

        private fun hashChars(s: String, from: Int, to: Int): Int {
            var h = FNV_OFFSET
            for (i in from until to) h = (h xor s[i].code) * FNV_PRIME
            return mix(h)
        }

        /** Spreads the bits: the table uses the low ones */
        private fun mix(h: Int) = h xor (h ushr 16)

        private companion object {
            const val FNV_OFFSET = -0x7ee3623b // 0x811C9DC5
            const val FNV_PRIME = 0x01000193
        }
    }
}

/**
 * Turns the text of the Public Suffix List (public_suffix_list.dat) into the compact file
 * [PublicSuffixList.load] reads: a header line `PSL1 <version>`, then three sections, the plain rules, the
 * wildcard rules without `*.` and the exception rules without `!`, each a line with the number of its
 * rules and the rules sorted, one per line, lowercase ASCII (punycode).
 */
object PublicSuffixListCompiler {
    fun compile(listText: String): ByteArray {
        var version = ""
        val exact = TreeSet<String>()
        val wildcard = TreeSet<String>()
        val exception = TreeSet<String>()
        for (raw in listText.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("// VERSION:")) version = line.substringAfter(':').trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            //a rule ends at the first whitespace
            val rule = line.split(' ', '\t')[0].lowercase(Locale.ROOT)
            when {
                rule.startsWith("!") -> exception += toAscii(rule.substring(1))
                rule.startsWith("*.") -> wildcard += toAscii(rule.substring(2))
                else -> exact += toAscii(rule)
            }
        }
        val out = StringBuilder()
        out.append(PublicSuffixList.MAGIC).append(' ').append(version).append('\n')
        for (section in listOf(exact, wildcard, exception)) {
            out.append(section.size).append('\n')
            for (rule in section) out.append(rule).append('\n')
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun toAscii(rule: String): String {
        val ascii = IDN.toASCII(rule, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        require(ascii.isNotEmpty() && ascii.all { it.code in 0x21..0x7E && it != '*' }) { "unsupported rule: $rule" }
        return ascii
    }
}
