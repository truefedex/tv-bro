package com.phlox.tvwebbrowser.adblock

import com.phlox.tvwebbrowser.adblock.engine.EngineFile
import com.phlox.tvwebbrowser.adblock.engine.FilterFlags
import com.phlox.tvwebbrowser.adblock.engine.Hashing
import com.phlox.tvwebbrowser.adblock.engine.IndexTable
import com.phlox.tvwebbrowser.adblock.engine.PatternMatcher
import com.phlox.tvwebbrowser.adblock.engine.Tables
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import java.io.File
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.regex.Pattern

/** What [FilterEngine.match] decided */
object MatchResult {
    /** No filter matched */
    const val NONE = 0
    const val BLOCK = 1
    /** A blocking filter matched, and an exception too (or the page is an exception) */
    const val ALLOW = 2
}

/**
 * The compiled filters of the lists ([FilterCompiler]): request filters in flat arrays and a string pool,
 * found through indexes by host and by the rarest token of each filter.
 *
 * Immutable and thread-safe: the engines of WebView ask from several threads at once, and a new engine
 * replaces this one by a plain write of a reference. Checks allocate nothing but the lowercase url (and what
 * a regular expression needs).
 */
class FilterEngine internal constructor(
    private val flags: IntArray,
    private val patternOffsets: IntArray,
    private val patternLengths: IntArray,
    /** The offset of the domain list of each filter in [domainLists], -1 for none */
    private val domainRefs: IntArray,
    private val pool: ByteArray,
    /** Per list: the start and size of its included domains, the start and size of its excluded ones in [domainHashes] */
    private val domainLists: IntArray,
    /** Sorted within each part of each list */
    private val domainHashes: LongArray,
    /** The ids of the regex filters, sorted: the index of a filter here is its slot in [regexes] */
    private val regexIds: IntArray,
    /**
     * For each regex filter: the offset and the length in [pool] of a literal every match contains (length 0:
     * none), checked first: regular expressions are slow on Android (ICU through JNI)
     */
    private val regexLiterals: IntArray,
    private val tables: Array<IndexTable>,
    private val psl: PublicSuffixList,
    /** Blocking filters for pages themselves (`$document`) */
    private val pageBlockingFilters: Int,
    /** Of the lists it was compiled from, given by the app (see [FilterCompiler.build]) */
    val fingerprint: Long,
    val createdAt: Long,
    private val texts: Array<String>?,
) {
    private val regexes = AtomicReferenceArray<Pattern>(regexIds.size)
    private val scratch = object : ThreadLocal<Scratch>() {
        override fun initialValue() = Scratch()
    }

    val filterCount: Int get() = flags.size

    /** Whether some filters block pages themselves: engines that can skip the check of main frames must not */
    val blocksPages: Boolean get() = pageBlockingFilters > 0
    val regexCount: Int get() = regexIds.size

    /** The size of the arrays of the engine, in bytes */
    val memoryBytes: Long
        get() {
            var bytes = (flags.size + patternOffsets.size + patternLengths.size + domainRefs.size + domainLists.size +
                    regexIds.size + regexLiterals.size) * 4L + pool.size + domainHashes.size * 8L
            for (t in tables) for (array in t.arrays()) bytes += array.size * 4L
            return bytes
        }

    /** The sizes of the bucket of filters without a token in each table (the filters every request checks) */
    val unkeyedFilterCounts: IntArray get() = IntArray(Tables.COUNT) { tables[it].noTokenSize }

    /**
     * Whether a request is an ad.
     *
     * @param url the url of the request
     * @param host its host (lowercase, punycode)
     * @param pageUrl the url of the page (or frame) that made it; null if not known
     * @param pageHost the host of [pageUrl]
     * @param types its [ResourceType], or the types it may be when the engine does not tell
     * @return a [MatchResult]
     */
    fun match(url: String, host: String, pageUrl: String?, pageHost: String?, types: Int): Int = try {
        matchFilter(url, host, pageUrl, pageHost, types).let { if (it == NO_FILTER) MatchResult.NONE else resultOf(it) }
    } catch (e: RuntimeException) {
        //never crash the thread of the web engine: a damaged engine blocks nothing
        errorCount.incrementAndGet()
        MatchResult.NONE
    }

    /** How many checks failed with an exception (an engine file of a buggy version) */
    val errors: Int get() = errorCount.get()
    private val errorCount = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The id of the filter that decides about the request (as [match]), [NO_FILTER] if none does; a page
     * exception gives [PAGE_EXCEPTION]. For debugging: [filterText].
     */
    fun matchFilter(url: String, host: String, pageUrl: String?, pageHost: String?, types: Int): Int {
        val s = scratch.get()!!
        s.prepare(url, host, pageHost)
        //most requests match nothing: the page and the exceptions are asked only for a block
        val block = find(s, tables[Tables.BLOCK], types)
        if (block < 0) return NO_FILTER
        if (pageUrl != null && pageHost != null && tables[Tables.PAGE].filterCount > 0 &&
            pageExceptions(s.page, pageUrl, pageHost) and ResourceType.DOCUMENT != 0) {
            return PAGE_EXCEPTION
        }
        if (flags[block] and FilterFlags.IMPORTANT != 0) return block
        val allow = find(s, tables[Tables.ALLOW], types)
        if (allow < 0) return block
        //an important filter overrides the exception
        val important = find(s, tables[Tables.IMPORTANT], types)
        return if (important >= 0) important else allow
    }

    /** The line of a filter, when the compiler kept them ([FilterCompiler] keepTexts) */
    fun filterText(id: Int): String? = when {
        id == PAGE_EXCEPTION -> "(page exception)"
        id < 0 -> null
        else -> texts?.get(id)
    }

    /**
     * The exceptions of a page: [ResourceType.DOCUMENT] (nothing is blocked on it), [ELEMHIDE] (no element
     * hiding), [GENERICHIDE] (no generic element hiding), as bits
     */
    fun pageExceptions(pageUrl: String, pageHost: String): Int = try {
        pageExceptions(scratch.get()!!.page, pageUrl, pageHost)
    } catch (e: RuntimeException) {
        errorCount.incrementAndGet()
        0
    }

    private fun pageExceptions(s: Scratch, pageUrl: String, pageHost: String): Int {
        if (pageUrl == s.lastPageUrl && pageHost == s.lastPageHost) return s.lastPageExceptions
        s.prepare(pageUrl, pageHost, pageHost)
        var result = 0
        forEachCandidate(s, tables[Tables.PAGE]) { id, position ->
            val f = flags[id]
            if (checkOptions(s, id, f) && matchesPattern(s, id, f, position)) {
                if (f and ResourceType.DOCUMENT != 0) result = result or ResourceType.DOCUMENT
                if (f and FilterFlags.ELEMHIDE != 0) result = result or ELEMHIDE
                if (f and FilterFlags.GENERICHIDE != 0) result = result or GENERICHIDE
            }
            false
        }
        s.lastPageUrl = pageUrl
        s.lastPageHost = pageHost
        s.lastPageExceptions = result
        return result
    }

    private fun resultOf(id: Int): Int = when {
        id == PAGE_EXCEPTION -> MatchResult.ALLOW
        flags[id] and FilterFlags.EXCEPTION != 0 -> MatchResult.ALLOW
        else -> MatchResult.BLOCK
    }

    /** The first filter of the [table] matching the prepared request, -1 if none */
    private fun find(s: Scratch, table: IndexTable, types: Int): Int {
        if (table.filterCount == 0) return -1
        var found = -1
        forEachCandidate(s, table) { id, position ->
            val f = flags[id]
            if (f and types and FilterFlags.TYPES != 0 && checkOptions(s, id, f) && matchesPattern(s, id, f, position)) {
                found = id
                true
            } else {
                false
            }
        }
        return found
    }

    /**
     * Gives [check] the filters of the [table] the prepared request may match, with where the url has the token
     * they were found by (-1 for filters found otherwise), until it returns true
     */
    private inline fun forEachCandidate(s: Scratch, table: IndexTable, check: (Int, Int) -> Boolean) {
        val starts = table.bucketStarts
        val filters = table.bucketFilters
        for (i in 0 until s.hostSuffixCount) {
            val slot = table.hostSlot(s.hostSuffixHashes[i])
            if (slot == 0) continue
            //the host of a host filter must be the suffix, not just have its hash
            val from = s.hostSuffixStarts[i]
            if (slot < 0) {
                val id = -slot - 1
                if (hostEquals(id, s.host, from) && check(id, -1)) return
            } else {
                for (k in starts[slot - 1] until starts[slot]) {
                    val id = filters[k]
                    if (hostEquals(id, s.host, from) && check(id, -1)) return
                }
            }
        }
        for (i in 0 until s.tokenCount) {
            val slot = table.tokenSlot(s.tokens[i])
            if (slot == 0) continue
            val position = s.tokenPositions[i]
            if (slot < 0) {
                if (check(-slot - 1, position)) return
            } else {
                for (k in starts[slot - 1] until starts[slot]) if (check(filters[k], position)) return
            }
        }
        val noToken = table.noTokenBucket
        if (noToken >= 0) {
            for (k in starts[noToken] until starts[noToken + 1]) if (check(filters[k], -1)) return
        }
    }

    private fun hostEquals(id: Int, host: String, from: Int): Boolean {
        val off = patternOffsets[id]
        val len = patternLengths[id]
        if (host.length - from != len) return false
        for (i in 0 until len) if (pool[off + i].toInt() != host[from + i].code) return false
        return true
    }

    /** The party and the domains of the filter */
    private fun checkOptions(s: Scratch, id: Int, f: Int): Boolean {
        if (f and (FilterFlags.THIRD_PARTY or FilterFlags.FIRST_PARTY) != 0) {
            //without a page the party of the request is not known
            val thirdParty = s.thirdParty(psl) ?: return false
            if (f and FilterFlags.THIRD_PARTY != 0 && !thirdParty) return false
            if (f and FilterFlags.FIRST_PARTY != 0 && thirdParty) return false
        }
        val domains = domainRefs[id]
        return domains < 0 || domainsMatch(s, domains)
    }

    /**
     * Whether the domain list applies on the page: the longest suffix of the page host in the list decides
     * (`domain=example.com|~sub.example.com`), and a list of only excluded domains applies elsewhere
     */
    private fun domainsMatch(s: Scratch, list: Int): Boolean {
        val includeStart = domainLists[list]
        val includeCount = domainLists[list + 1]
        val excludeStart = domainLists[list + 2]
        val excludeCount = domainLists[list + 3]
        val count = s.pageSuffixCount()
        for (i in 0 until count) {
            val hash = s.pageSuffixHashes[i]
            if (excludeCount > 0 && contains(excludeStart, excludeCount, hash)) return false
            if (includeCount > 0 && contains(includeStart, includeCount, hash)) return true
        }
        return includeCount == 0
    }

    private fun contains(start: Int, count: Int, hash: Long): Boolean =
        java.util.Arrays.binarySearch(domainHashes, start, start + count, hash) >= 0

    /** [position]: where the url has the token the filter was found by, -1 if not found by a token */
    private fun matchesPattern(s: Scratch, id: Int, f: Int, position: Int): Boolean {
        if (f and FilterFlags.REGEX != 0) return matchesRegex(s, id, f)
        val url = if (f and FilterFlags.MATCH_CASE != 0 && s.originalUrl.length == s.url.length) s.originalUrl else s.url
        val offset = FilterFlags.tokenOffset(f)
        if (position >= 0 && offset != FilterFlags.TOKEN_OFFSET_UNKNOWN) {
            //the pattern can only start that far before the token
            return PatternMatcher.matchesAt(url, position - offset, pool, patternOffsets[id], patternLengths[id],
                f and FilterFlags.ANCHOR_START != 0, f and FilterFlags.ANCHOR_HOST != 0, f and FilterFlags.ANCHOR_END != 0,
                f and FilterFlags.WILDCARD != 0, s.hostStart, s.hostEnd)
        }
        return PatternMatcher.matches(url, pool, patternOffsets[id], patternLengths[id],
            f and FilterFlags.ANCHOR_START != 0, f and FilterFlags.ANCHOR_HOST != 0, f and FilterFlags.ANCHOR_END != 0,
            f and FilterFlags.WILDCARD != 0, s.hostStart, s.hostEnd)
    }

    private fun matchesRegex(s: Scratch, id: Int, f: Int): Boolean {
        if (s.originalUrl.length > MAX_REGEX_URL_LENGTH) return false
        val slot = java.util.Arrays.binarySearch(regexIds, id)
        val literalLength = regexLiterals[slot * 2 + 1]
        if (literalLength > 0) {
            val url = if (f and FilterFlags.MATCH_CASE != 0) s.originalUrl else s.url
            if (!containsPoolBytes(url, regexLiterals[slot * 2], literalLength)) return false
        }
        var regex = regexes.get(slot)
        if (regex == null) {
            val source = String(pool, patternOffsets[id], patternLengths[id], Charsets.UTF_8)
            val options = if (f and FilterFlags.MATCH_CASE != 0) 0 else Pattern.CASE_INSENSITIVE
            regex = try {
                Pattern.compile(source, options)
            } catch (e: Exception) {
                NEVER
            }
            //two threads may compile it both: either result is the same
            regexes.set(slot, regex)
        }
        return regex !== NEVER && regex!!.matcher(s.originalUrl).find()
    }

    private fun containsPoolBytes(url: String, off: Int, len: Int): Boolean {
        val first = pool[off].toInt().toChar()
        var i = url.indexOf(first)
        while (i >= 0 && i + len <= url.length) {
            var k = 1
            while (k < len && url[i + k].code == pool[off + k].toInt()) k++
            if (k == len) return true
            i = url.indexOf(first, i + 1)
        }
        return false
    }

    /** Writes the engine to the [file] atomically (a temporary file, then a rename) */
    fun writeTo(file: File) {
        EngineFile.write(file, fingerprint, createdAt, sections())
    }

    private fun sections(): Map<Int, Any> {
        val sections = HashMap<Int, Any>()
        sections[EngineFile.FLAGS] = flags
        sections[EngineFile.PATTERN_OFFSETS] = patternOffsets
        sections[EngineFile.PATTERN_LENGTHS] = patternLengths
        sections[EngineFile.DOMAIN_REFS] = domainRefs
        sections[EngineFile.POOL] = pool
        sections[EngineFile.DOMAIN_LISTS] = domainLists
        sections[EngineFile.DOMAIN_HASHES] = domainHashes
        sections[EngineFile.REGEX_IDS] = regexIds
        sections[EngineFile.REGEX_LITERALS] = regexLiterals
        sections[EngineFile.INFO] = intArrayOf(pageBlockingFilters)
        for (t in tables.indices) {
            tables[t].arrays().forEachIndexed { i, array -> sections[EngineFile.tableSection(t, i)] = array }
        }
        return sections
    }

    companion object {
        const val NO_FILTER = -1
        const val PAGE_EXCEPTION = -2
        /** Bits of [pageExceptions] (besides [ResourceType.DOCUMENT]) */
        const val ELEMHIDE = 1 shl 12
        const val GENERICHIDE = 1 shl 13
        private const val MAX_REGEX_URL_LENGTH = 4096
        private val NEVER: Pattern = Pattern.compile("a^")

        /**
         * Loads an engine [writeTo] wrote. Throws [AdblockFormatException] for a file of another format version,
         * a damaged or a truncated one.
         */
        fun load(file: File, psl: PublicSuffixList): FilterEngine = read(file.readBytes(), psl)

        fun read(bytes: ByteArray, psl: PublicSuffixList): FilterEngine {
            val content = EngineFile.read(bytes)
            val s = content.sections
            try {
                val tables = Array(Tables.COUNT) { t ->
                    IndexTable.of((0 until EngineFile.TABLE_ARRAYS).map { s.getValue(EngineFile.tableSection(t, it)) })
                }
                val engine = FilterEngine(
                    flags = s.getValue(EngineFile.FLAGS) as IntArray,
                    patternOffsets = s.getValue(EngineFile.PATTERN_OFFSETS) as IntArray,
                    patternLengths = s.getValue(EngineFile.PATTERN_LENGTHS) as IntArray,
                    domainRefs = s.getValue(EngineFile.DOMAIN_REFS) as IntArray,
                    pool = s.getValue(EngineFile.POOL) as ByteArray,
                    domainLists = s.getValue(EngineFile.DOMAIN_LISTS) as IntArray,
                    domainHashes = s.getValue(EngineFile.DOMAIN_HASHES) as LongArray,
                    regexIds = s.getValue(EngineFile.REGEX_IDS) as IntArray,
                    regexLiterals = s.getValue(EngineFile.REGEX_LITERALS) as IntArray,
                    tables = tables, psl = psl, pageBlockingFilters = (s.getValue(EngineFile.INFO) as IntArray)[0],
                    fingerprint = content.fingerprint, createdAt = content.createdAt,
                    texts = null,
                )
                engine.validate()
                return engine
            } catch (e: AdblockFormatException) {
                throw e
            } catch (e: Exception) {
                //a missing section or an array of another type
                throw AdblockFormatException("bad engine file: $e")
            }
        }
    }

    /**
     * Checks that the arrays fit together. Only their sizes: element by element it would take most of the
     * load on a TV (a load runs once, in the interpreter of ART). The CRC of the file already guards against
     * damage; against a file of a buggy version the checks catch their exceptions ([match]).
     */
    private fun validate() {
        val n = flags.size
        fun check(ok: Boolean, what: String) {
            if (!ok) throw AdblockFormatException("bad engine file: $what")
        }
        check(patternOffsets.size == n && patternLengths.size == n && domainRefs.size == n, "filter arrays")
        check(domainLists.size % 4 == 0, "domain lists")
        check(regexLiterals.size == regexIds.size * 2 && regexIds.size <= n, "regex")
        for (t in tables) {
            check(t.bucketStarts.isNotEmpty() && t.bucketStarts.last() == t.bucketFilters.size, "buckets")
            check(t.hostKeys.size == t.hostSlots.size && t.tokenKeys.size == t.tokenSlots.size, "slots")
            check(t.noTokenBucket in -1 until t.bucketStarts.size - 1, "no-token bucket")
        }
    }

    /** What a thread knows about the request it checks, reused from request to request */
    private class Scratch {
        var originalUrl = ""
        var url = ""
        var host = ""
        var pageHost: String? = null
        var hostStart = -1
        var hostEnd = -1
        var tokens = IntArray(64)
        var tokenPositions = IntArray(64)
        var tokenCount = 0
        val hostSuffixStarts = IntArray(MAX_LABELS)
        val hostSuffixHashes = IntArray(MAX_LABELS)
        var hostSuffixCount = 0
        val pageSuffixHashes = LongArray(MAX_LABELS)
        private var pageSuffixes = -1
        private var thirdParty = UNKNOWN

        var lastPageUrl: String? = null
        var lastPageHost: String? = null
        var lastPageExceptions = 0

        /** For the page exceptions: they are checked between the checks of the request */
        val page: Scratch by lazy(LazyThreadSafetyMode.NONE) { Scratch() }

        /** The page host of [pageSuffixHashes] and of [pageSiteStart]: requests of one page share them */
        private var pageSuffixesHost: String? = null
        private var pageSiteHost: String? = null
        private var pageSiteStart = 0

        fun prepare(requestUrl: String, requestHost: String, page: String?) {
            originalUrl = requestUrl
            //patterns are lowercase ASCII (other characters percent-encoded), as urls are
            url = Hashing.asciiLowercase(requestUrl)
            host = Hashing.asciiLowercase(requestHost)
            pageHost = page?.let(Hashing::asciiLowercase)
            if (pageHost != pageSuffixesHost) pageSuffixes = -1
            thirdParty = UNKNOWN
            findHost()
            tokenize()
            hostSuffixes()
        }

        private fun findHost() {
            val scheme = url.indexOf("://")
            val from = if (scheme < 0) 0 else scheme + 3
            hostStart = if (url.startsWith(host, from)) from else if (host.isEmpty()) -1 else url.indexOf(host, from)
            hostEnd = if (hostStart < 0) -1 else hostStart + host.length
        }

        /** The hashes of the tokens of the url (as [Hashing.token], in the same pass) */
        private fun tokenize() {
            tokenCount = 0
            val u = url
            //as uBlock Origin: the tokens of a longer url (its query) find no more filters worth their time
            val length = minOf(u.length, MAX_TOKENIZED_LENGTH)
            var i = 0
            while (i < length) {
                var c = u[i]
                if (!Hashing.isTokenChar(c)) {
                    i++
                    continue
                }
                var h = Hashing.TOKEN_START
                var n = 0
                var j = i
                while (true) {
                    if (n < Hashing.TOKEN_HASH_CHARS) {
                        h = Hashing.tokenStep(h, c)
                        n++
                    }
                    j++
                    if (j == length) break
                    c = u[j]
                    if (!Hashing.isTokenChar(c)) break
                }
                if (j - i >= 2) {
                    if (tokenCount == tokens.size) {
                        tokens = tokens.copyOf(tokens.size * 2)
                        tokenPositions = tokenPositions.copyOf(tokens.size)
                    }
                    tokenPositions[tokenCount] = i
                    tokens[tokenCount++] = Hashing.finishToken(h)
                }
                i = j
            }
        }

        /**
         * The host and its suffixes with a dot, for host filters, longest first: one pass from the end of the
         * host ([Hashing.host] hashes from the last character)
         */
        private fun hostSuffixes() {
            hostSuffixCount = 0
            val h = host
            var hash = Hashing.TOKEN_START
            var dots = 0
            //the suffixes, shortest first, then turned around
            var count = 0
            for (i in h.length - 1 downTo 0) {
                val c = h[i]
                if (c == '.') {
                    dots++
                } else if (i == 0 || h[i - 1] == '.') {
                    //a suffix starts here; the top-level domain alone is no host filter
                    hash = Hashing.tokenStep(hash, c)
                    if (dots > 0 && count < MAX_LABELS) {
                        hostSuffixStarts[count] = i
                        hostSuffixHashes[count] = Hashing.finishToken(hash)
                        count++
                    }
                    continue
                }
                hash = Hashing.tokenStep(hash, c)
            }
            for (k in 0 until count / 2) {
                val start = hostSuffixStarts[k]
                hostSuffixStarts[k] = hostSuffixStarts[count - 1 - k]
                hostSuffixStarts[count - 1 - k] = start
                val hashK = hostSuffixHashes[k]
                hostSuffixHashes[k] = hostSuffixHashes[count - 1 - k]
                hostSuffixHashes[count - 1 - k] = hashK
            }
            hostSuffixCount = count
        }

        /** The page host and all its suffixes, longest first, for domain options; 0 without a page */
        fun pageSuffixCount(): Int {
            if (pageSuffixes >= 0) return pageSuffixes
            val p = pageHost
            pageSuffixesHost = p
            var count = 0
            if (p != null && p.isNotEmpty()) {
                var start = 0
                while (count < MAX_LABELS) {
                    pageSuffixHashes[count++] = Hashing.domain(p, start, p.length)
                    val dot = p.indexOf('.', start)
                    if (dot < 0) break
                    start = dot + 1
                }
            }
            pageSuffixes = count
            return count
        }

        /** Whether the request goes to another site than its page; null without a page */
        fun thirdParty(psl: PublicSuffixList): Boolean? {
            if (thirdParty == UNKNOWN) {
                val p = pageHost
                thirdParty = if (p == null || p.isEmpty()) NO_PAGE else if (sameSite(psl, host, p)) 0 else 1
            }
            return if (thirdParty == NO_PAGE) null else thirdParty == 1
        }

        /** [PublicSuffixList.isSameSite], with the site of the page kept for its next requests */
        private fun sameSite(psl: PublicSuffixList, requestHost: String, page: String): Boolean {
            if (page != pageSiteHost) {
                pageSiteStart = psl.registrableDomainStart(page)
                pageSiteHost = page
            }
            val start = psl.registrableDomainStart(requestHost)
            val length = requestHost.length - start
            return length == page.length - pageSiteStart && requestHost.regionMatches(start, page, pageSiteStart, length)
        }

        private companion object {
            const val MAX_TOKENIZED_LENGTH = 2048
            const val MAX_LABELS = 16
            const val UNKNOWN = -1
            const val NO_PAGE = -2
        }
    }
}

/** An engine file of another format version, damaged or truncated */
class AdblockFormatException(message: String) : Exception(message)
