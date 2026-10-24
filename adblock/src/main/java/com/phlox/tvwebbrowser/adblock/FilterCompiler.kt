package com.phlox.tvwebbrowser.adblock

import com.phlox.tvwebbrowser.adblock.engine.ByteList
import com.phlox.tvwebbrowser.adblock.engine.FilterFlags
import com.phlox.tvwebbrowser.adblock.engine.Hashing
import com.phlox.tvwebbrowser.adblock.engine.IndexTable
import com.phlox.tvwebbrowser.adblock.engine.IntList
import com.phlox.tvwebbrowser.adblock.engine.LongList
import com.phlox.tvwebbrowser.adblock.engine.RegexLiterals
import com.phlox.tvwebbrowser.adblock.engine.Tables
import com.phlox.tvwebbrowser.adblock.engine.CosmeticCompiler
import com.phlox.tvwebbrowser.adblock.parser.CosmeticFilter
import com.phlox.tvwebbrowser.adblock.parser.DomainList
import com.phlox.tvwebbrowser.adblock.parser.FilterParser
import com.phlox.tvwebbrowser.adblock.parser.NetworkFilter
import com.phlox.tvwebbrowser.adblock.parser.ParseStats
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList

/**
 * Compiles filter lists into a [FilterEngine]. The filters are kept in flat arrays as they come (no object
 * per filter), each with the tokens it could be found by; [build] gives each filter its rarest token and
 * builds the indexes.
 *
 * Element hiding filters go into a [CosmeticIndex][com.phlox.tvwebbrowser.adblock.engine.CosmeticIndex].
 *
 * @param keepTexts keep the lines of the filters in the engine ([FilterEngine.filterText]), for tests and debugging
 */
class FilterCompiler(private val keepTexts: Boolean = false) {

    private val flags = IntList()
    private val patternOffsets = IntList()
    private val patternLengths = IntList()
    private val domainRefs = IntList()
    private val pool = ByteList()
    /** The candidate tokens of filter i are `tokens[tokenStarts[i] until tokenStarts[i + 1]]` */
    private val tokenStarts = IntList()
    private val tokens = IntList()
    /** Where each candidate token starts in its pattern ([FilterFlags.TOKEN_OFFSET_UNKNOWN] after a `*`) */
    private val tokenOffsets = IntList()
    /** In how many filters each token is a candidate */
    private val tokenCounts = HashMap<Int, Int>()
    private val domainHashes = LongList()
    private val domainLists = IntList()
    private val domainListIds = HashMap<String, Int>()
    /** For each regex filter (in the order of ids): the offset and length of its required literal in the pool */
    private val regexLiteralRefs = IntList(64)
    private val texts = if (keepTexts) ArrayList<String>() else null
    private val cosmetics = CosmeticCompiler(::domainList)

    val filterCount: Int get() = flags.size

    /** Parses and adds the filters of a list, line by line */
    fun addList(lines: Sequence<String>): ParseStats {
        val parser = FilterParser()
        return parser.parseList(lines) {
            when (it) {
                is NetworkFilter -> add(it)
                is CosmeticFilter -> cosmetics.add(it)
            }
        }
    }

    fun add(filter: NetworkFilter) {
        var f = filter.types
        when (filter.thirdParty) {
            true -> f = f or FilterFlags.THIRD_PARTY
            false -> f = f or FilterFlags.FIRST_PARTY
            null -> {}
        }
        if (filter.important) f = f or FilterFlags.IMPORTANT
        if (filter.matchCase) f = f or FilterFlags.MATCH_CASE
        if (filter.anchorHost) f = f or FilterFlags.ANCHOR_HOST
        if (filter.anchorStart) f = f or FilterFlags.ANCHOR_START
        if (filter.anchorEnd) f = f or FilterFlags.ANCHOR_END
        if (filter.isRegex) f = f or FilterFlags.REGEX
        if (filter.isException) f = f or FilterFlags.EXCEPTION
        if (filter.elemHide) f = f or FilterFlags.ELEMHIDE
        if (filter.genericHide) f = f or FilterFlags.GENERICHIDE
        if (filter.isHostOnly) f = f or FilterFlags.HOST_ONLY
        val pattern = if (filter.isRegex) filter.pattern else encodeNonAscii(filter.pattern, filter.matchCase)
        if (!filter.isRegex && pattern.contains('*')) f = f or FilterFlags.WILDCARD
        //a filter indexed by a token gets its offset in build()
        f = f or (FilterFlags.TOKEN_OFFSET_UNKNOWN shl FilterFlags.TOKEN_OFFSET_SHIFT)
        flags.add(f)

        val bytes = pattern.toByteArray(Charsets.UTF_8)
        patternOffsets.add(pool.size)
        for (b in bytes) pool.add(b)
        patternLengths.add(bytes.size)
        if (filter.isRegex) {
            //checked before the regular expression, which is slow on Android (ICU through JNI)
            val literal = RegexLiterals.required(pattern)?.let { if (filter.matchCase) it else it.lowercase() }
            regexLiteralRefs.add(pool.size)
            val literalBytes = literal?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            for (b in literalBytes) pool.add(b)
            regexLiteralRefs.add(literalBytes.size)
        }
        domainRefs.add(filter.domains?.let(::domainList) ?: -1)

        tokenStarts.add(tokens.size)
        if (!filter.isHostOnly && !filter.isRegex) {
            addTokens(pattern.lowercase(), filter.anchorStart || filter.anchorHost, filter.anchorEnd)
        }
        texts?.add(filter.text)
    }

    /**
     * The tokens of the [pattern] a matching url surely has as whole tokens: runs of token characters that
     * neither `*` nor an unanchored end of the pattern touches
     */
    private fun addTokens(pattern: String, anchoredStart: Boolean, anchoredEnd: Boolean) {
        var i = 0
        var added = 0
        var afterStar = false
        while (i < pattern.length && added < MAX_TOKENS_PER_FILTER) {
            if (!Hashing.isTokenChar(pattern[i])) {
                if (pattern[i] == '*') afterStar = true
                i++
                continue
            }
            var j = i
            while (j < pattern.length && Hashing.isTokenChar(pattern[j])) j++
            val before = if (i == 0) anchoredStart else pattern[i - 1] != '*'
            val after = if (j == pattern.length) anchoredEnd else pattern[j] != '*'
            if (before && after && j - i >= MIN_TOKEN_LENGTH) {
                val hash = Hashing.token(pattern, i, j)
                tokens.add(hash)
                tokenOffsets.add(if (afterStar || i >= FilterFlags.TOKEN_OFFSET_UNKNOWN) FilterFlags.TOKEN_OFFSET_UNKNOWN else i)
                tokenCounts[hash] = (tokenCounts[hash] ?: 0) + 1
                added++
            }
            i = j
        }
    }

    /** The offset of the [domains] in the domain lists, the same for equal lists */
    private fun domainList(domains: DomainList): Int = domainListIds.getOrPut(domains.toString()) {
        val offset = domainLists.size
        for (part in listOf(domains.include, domains.exclude)) {
            val start = domainHashes.size
            for (hash in part.map { Hashing.domain(it) }.distinct().sorted()) domainHashes.add(hash)
            domainLists.add(start)
            domainLists.add(domainHashes.size - start)
        }
        offset
    }

    /** Builds the engine; the compiler should not be used after it */
    fun build(psl: PublicSuffixList, fingerprint: Long = 0): FilterEngine {
        tokenStarts.add(tokens.size)
        val n = flags.size
        val poolBytes = pool.toArray()
        val flagArray = flags.toArray()
        val offsets = patternOffsets.toArray()
        val lengths = patternLengths.toArray()

        //the members of each table, keyed by host, by token or by nothing
        val hostKeyed = Array(Tables.COUNT) { HashMap<Int, IntList>() }
        val tokenKeyed = Array(Tables.COUNT) { HashMap<Int, IntList>() }
        val unkeyed = Array(Tables.COUNT) { IntList(256) }
        val regexIds = IntList(256)
        for (id in 0 until n) {
            val f = flagArray[id]
            if (f and FilterFlags.REGEX != 0) regexIds.add(id)
            var hostHash = 0
            var token = 0
            val kind = if (f and FilterFlags.HOST_ONLY != 0) {
                val host = String(poolBytes, offsets[id], lengths[id], Charsets.US_ASCII)
                hostHash = Hashing.host(host, 0, host.length)
                KEY_HOST
            } else {
                val best = bestToken(id)
                if (best < 0) KEY_NONE else {
                    token = tokens[best]
                    flagArray[id] = flagArray[id] and (FilterFlags.TOKEN_OFFSET_UNKNOWN shl FilterFlags.TOKEN_OFFSET_SHIFT).inv() or
                            (tokenOffsets[best] shl FilterFlags.TOKEN_OFFSET_SHIFT)
                    KEY_TOKEN
                }
            }
            for (table in tablesOf(f)) {
                when (kind) {
                    KEY_HOST -> hostKeyed[table].getOrPut(hostHash) { IntList(2) }.add(id)
                    KEY_TOKEN -> tokenKeyed[table].getOrPut(token) { IntList(2) }.add(id)
                    else -> unkeyed[table].add(id)
                }
            }
        }
        val tables = Array(Tables.COUNT) { t ->
            IndexTable.build(hostKeyed[t].mapValues { it.value.toArray() }, tokenKeyed[t].mapValues { it.value.toArray() },
                unkeyed[t].toArray())
        }
        return FilterEngine(
            flags = flagArray, patternOffsets = offsets, patternLengths = lengths, domainRefs = domainRefs.toArray(),
            pool = poolBytes, domainLists = domainLists.toArray(), domainHashes = domainHashes.toArray(),
            regexIds = regexIds.toArray(), regexLiterals = regexLiteralRefs.toArray(), tables = tables, psl = psl,
            pageBlockingFilters = (0 until n).count {
                val f = flagArray[it]
                f and FilterFlags.EXCEPTION == 0 && f and ResourceType.DOCUMENT != 0
            },
            cosmetics = cosmetics.build(), fingerprint = fingerprint,
            createdAt = System.currentTimeMillis(), texts = texts?.toTypedArray(),
        )
    }

    /** The index in [tokens] of the candidate of the filter that is in the fewest filters; -1 if none */
    private fun bestToken(id: Int): Int {
        var best = -1
        var bestScore = Int.MAX_VALUE
        for (i in tokenStarts[id] until tokenStarts[id + 1]) {
            val hash = tokens[i]
            val score = (tokenCounts[hash] ?: 0) + (if (hash in COMMON_URL_TOKENS) COMMON_TOKEN_PENALTY else 0)
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    private fun tablesOf(flags: Int): List<Int> {
        if (flags and FilterFlags.EXCEPTION == 0) {
            return if (flags and FilterFlags.IMPORTANT != 0) listOf(Tables.BLOCK, Tables.IMPORTANT) else listOf(Tables.BLOCK)
        }
        val tables = ArrayList<Int>(2)
        val pageFlags = ResourceType.DOCUMENT or FilterFlags.ELEMHIDE or FilterFlags.GENERICHIDE
        if (flags and pageFlags != 0) tables += Tables.PAGE
        if (flags and FilterFlags.TYPES and ResourceType.DOCUMENT.inv() != 0) tables += Tables.ALLOW
        return tables
    }

    companion object {
        /**
         * Raise it whenever the compiler gives other arrays for the same lists (the parser too): apps mix it
         * into the fingerprint of their lists, so an engine file of an older compiler is built again.
         */
        const val VERSION = 5

        private const val KEY_NONE = 0
        private const val KEY_HOST = 1
        private const val KEY_TOKEN = 2
        private const val MAX_TOKENS_PER_FILTER = 8
        private const val MIN_TOKEN_LENGTH = 2
        private const val COMMON_TOKEN_PENALTY = 1_000_000

        /** Tokens of nearly every url: a filter found by one of them would be checked for most requests */
        private val COMMON_URL_TOKENS = listOf("http", "https", "www", "com", "net", "org", "js", "css", "html", "htm", "php",
            "jpg", "jpeg", "png", "gif", "webp", "svg", "json", "min", "static", "cdn", "img", "images", "api", "v1", "v2")
            .map { Hashing.token(it, 0, it.length) }.toSet()

        /** Non-ASCII characters as urls have them: percent-encoded UTF-8 (lowercase hex unless [upperHex]) */
        private fun encodeNonAscii(pattern: String, upperHex: Boolean): String {
            if (pattern.all { it.code < 0x80 }) return pattern
            val sb = StringBuilder()
            val digits = if (upperHex) "0123456789ABCDEF" else "0123456789abcdef"
            var i = 0
            while (i < pattern.length) {
                val codePoint = pattern.codePointAt(i)
                val chars = Character.charCount(codePoint)
                if (codePoint < 0x80) {
                    sb.append(codePoint.toChar())
                } else {
                    for (b in pattern.substring(i, i + chars).toByteArray(Charsets.UTF_8)) {
                        val v = b.toInt() and 0xFF
                        sb.append('%').append(digits[v shr 4]).append(digits[v and 0xF])
                    }
                }
                i += chars
            }
            return sb.toString()
        }
    }
}
