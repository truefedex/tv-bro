package com.phlox.tvwebbrowser.adblock.engine

import com.phlox.tvwebbrowser.adblock.parser.CosmeticFilter
import com.phlox.tvwebbrowser.adblock.parser.DomainList

/**
 * The element hiding filters, compiled: each selector once in a pool, and sorted tables of 64-bit keys
 * (hashes of domains, or of the class or id a generic selector needs) to selector ids:
 * - specific: the selectors of a domain (and its subdomains), with the domains excluded from them;
 * - exceptions: the selectors not hidden on a domain (`domain#@#selector`);
 * - generic: the selectors of all sites that need an element with a class or id (`##.ad-box`, `##div#ad >
 *   a`), found by the classes and ids a page has (the page asks for them as they appear);
 * - high: the other generic selectors (`##[data-ad]`), hidden on every page.
 * Exclusions are offsets of domain lists of the engine (-1: none).
 */
internal class CosmeticIndex(
    val selectorPool: ByteArray,
    val selectorOffsets: IntArray,
    val specificKeys: LongArray,
    val specificSelectors: IntArray,
    val specificExclusions: IntArray,
    val exceptionKeys: LongArray,
    val exceptionSelectors: IntArray,
    val genericKeys: LongArray,
    val genericSelectors: IntArray,
    val genericExclusions: IntArray,
    val highSelectors: IntArray,
    val highExclusions: IntArray,
) {
    val selectorCount: Int get() = selectorOffsets.size - 1
    val hasGeneric: Boolean get() = genericKeys.isNotEmpty()

    fun selector(id: Int): String =
        String(selectorPool, selectorOffsets[id], selectorOffsets[id + 1] - selectorOffsets[id], Charsets.UTF_8)

    /** The first index of [key] in the sorted [keys]; the entries of the key follow it */
    fun firstIndex(keys: LongArray, key: Long): Int {
        var low = 0
        var high = keys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keys[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    fun arrays(): List<Any> = listOf(selectorPool, selectorOffsets, specificKeys, specificSelectors, specificExclusions,
        exceptionKeys, exceptionSelectors, genericKeys, genericSelectors, genericExclusions, highSelectors, highExclusions)

    val memoryBytes: Long
        get() = arrays().sumOf {
            when (it) {
                is ByteArray -> it.size.toLong()
                is IntArray -> it.size * 4L
                is LongArray -> it.size * 8L
                else -> 0L
            }
        }

    companion object {
        const val ARRAYS = 12

        fun of(arrays: List<Any>) = CosmeticIndex(
            arrays[0] as ByteArray, arrays[1] as IntArray, arrays[2] as LongArray, arrays[3] as IntArray,
            arrays[4] as IntArray, arrays[5] as LongArray, arrays[6] as IntArray, arrays[7] as LongArray,
            arrays[8] as IntArray, arrays[9] as IntArray, arrays[10] as IntArray, arrays[11] as IntArray,
        )

        /**
         * The key of the class or id every element the generic [selector] matches has: the first one of its
         * first compound selector (`div.ad > a` needs class `ad`); null when it starts otherwise (an
         * attribute, a tag alone, an escaped name)
         */
        fun genericKey(selector: String): Long? {
            var i = 0
            //a tag name before it
            while (i < selector.length && (selector[i].isLetterOrDigit() || selector[i] == '-')) i++
            if (i >= selector.length) return null
            val prefix = selector[i]
            if (prefix != '.' && prefix != '#') return null
            val start = i + 1
            var end = start
            while (end < selector.length && isNameChar(selector[end])) end++
            if (end == start) return null
            if (end < selector.length && selector[end] !in NAME_ENDS) return null
            return Hashing.nameKey(prefix, selector, start, end)
        }

        private const val NAME_ENDS = " >+~.#[:"

        private fun isNameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' ||
                c.code >= 0x80
    }
}

/** Collects the element hiding filters of the lists for a [CosmeticIndex] */
internal class CosmeticCompiler(private val domainRef: (DomainList) -> Int) {
    private class Entry(val key: Long, val selector: Int, val exclusions: Int)

    private val selectorIds = HashMap<String, Int>()
    private val pool = ByteList(16 * 1024)
    private val offsets = IntList(1024).apply { add(0) }
    private val specific = ArrayList<Entry>()
    private val exceptions = ArrayList<Entry>()
    private val generic = ArrayList<Entry>()
    private val high = ArrayList<Entry>()
    /** `#@#selector` without domains: the selector is hidden nowhere */
    private val globalExceptions = HashSet<Int>()

    fun add(filter: CosmeticFilter) {
        val selector = selectorId(filter.selector)
        val include = filter.domains?.include.orEmpty()
        val exclude = filter.domains?.exclude.orEmpty()
        if (filter.isException) {
            if (include.isNotEmpty()) {
                for (domain in include) exceptions += Entry(Hashing.domain(domain), selector, -1)
            } else if (exclude.isEmpty()) {
                globalExceptions += selector
            }
            return
        }
        val exclusions = if (exclude.isEmpty()) -1 else domainRef(DomainList(emptyList(), exclude))
        if (include.isNotEmpty()) {
            for (domain in include) specific += Entry(Hashing.domain(domain), selector, exclusions)
            return
        }
        val key = CosmeticIndex.genericKey(filter.selector)
        if (key != null) generic += Entry(key, selector, exclusions) else high += Entry(0, selector, exclusions)
    }

    private fun selectorId(selector: String): Int = selectorIds.getOrPut(selector) {
        for (b in selector.toByteArray(Charsets.UTF_8)) pool.add(b)
        offsets.add(pool.size)
        offsets.size - 2
    }

    fun build(): CosmeticIndex {
        fun usable(entries: List<Entry>) = entries.filter { it.selector !in globalExceptions }.sortedBy { it.key }
        val specificEntries = usable(specific)
        val genericEntries = usable(generic)
        val highEntries = usable(high)
        val exceptionEntries = exceptions.sortedBy { it.key }
        return CosmeticIndex(
            selectorPool = pool.toArray(), selectorOffsets = offsets.toArray(),
            specificKeys = LongArray(specificEntries.size) { specificEntries[it].key },
            specificSelectors = IntArray(specificEntries.size) { specificEntries[it].selector },
            specificExclusions = IntArray(specificEntries.size) { specificEntries[it].exclusions },
            exceptionKeys = LongArray(exceptionEntries.size) { exceptionEntries[it].key },
            exceptionSelectors = IntArray(exceptionEntries.size) { exceptionEntries[it].selector },
            genericKeys = LongArray(genericEntries.size) { genericEntries[it].key },
            genericSelectors = IntArray(genericEntries.size) { genericEntries[it].selector },
            genericExclusions = IntArray(genericEntries.size) { genericEntries[it].exclusions },
            highSelectors = IntArray(highEntries.size) { highEntries[it].selector },
            highExclusions = IntArray(highEntries.size) { highEntries[it].exclusions },
        )
    }
}
