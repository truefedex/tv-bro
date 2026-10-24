package com.phlox.tvwebbrowser.adblock.engine

/**
 * Matches the patterns of filters (stored as ASCII bytes in the pool) against urls without regular
 * expressions and without allocating: `*` matches any characters, `^` a separator ([Hashing.isSeparator])
 * or the end of the url, the anchors fix where the pattern starts or ends.
 */
internal object PatternMatcher {
    private const val STAR = '*'.code.toByte()
    private const val CARET = '^'.code.toByte()

    /**
     * Whether the pattern `pool[off until off + len]` matches the [url].
     * [hostStart] and [hostEnd] are where the host is in the url (-1 if it is not there), for [anchorHost].
     */
    fun matches(
        url: String, pool: ByteArray, off: Int, len: Int,
        anchorStart: Boolean, anchorHost: Boolean, anchorEnd: Boolean,
        hostStart: Int, hostEnd: Int,
    ): Boolean {
        if (len == 0) return true
        val end = off + len
        val firstEnd = indexOfStar(pool, off, end)
        when {
            anchorStart -> return tryAt(url, 0, pool, off, firstEnd, end, anchorEnd)
            anchorHost -> {
                if (hostStart < 0) return false
                //at the start of the host or of one of its labels
                for (p in hostStart until hostEnd) {
                    if ((p == hostStart || url[p - 1] == '.') && tryAt(url, p, pool, off, firstEnd, end, anchorEnd)) {
                        return true
                    }
                }
                return false
            }
            else -> {
                val first = pool[off]
                var p = 0
                while (p <= url.length) {
                    if (first != CARET) {
                        p = url.indexOf(first.toInt().toChar(), p)
                        if (p < 0) return false
                    }
                    if (tryAt(url, p, pool, off, firstEnd, end, anchorEnd)) return true
                    p++
                }
                return false
            }
        }
    }

    /** Whether the pattern matches with its first segment ([off] until [firstEnd]) at [p] */
    private fun tryAt(url: String, p: Int, pool: ByteArray, off: Int, firstEnd: Int, end: Int, anchorEnd: Boolean): Boolean {
        val e = matchSegment(url, p, pool, off, firstEnd)
        if (e < 0) return false
        return matchRest(url, e, pool, firstEnd, end, anchorEnd)
    }

    /**
     * Matches the segments after the first one, from [segStart] (a '*' or the end): each one at its leftmost
     * place, which leaves the most room to the next ones; the last one of an [anchorEnd] pattern at the end.
     */
    private fun matchRest(url: String, from: Int, pool: ByteArray, segStart: Int, end: Int, anchorEnd: Boolean): Boolean {
        if (segStart == end) return !anchorEnd || from == url.length
        var position = from
        var star = segStart
        while (true) {
            val begin = star + 1
            val segEnd = indexOfStar(pool, begin, end)
            val last = segEnd == end
            if (last && anchorEnd) {
                for (q in position..url.length) {
                    if (matchSegment(url, q, pool, begin, segEnd) == url.length) return true
                }
                return false
            }
            var found = -1
            var q = position
            while (q <= url.length) {
                val e = matchSegment(url, q, pool, begin, segEnd)
                if (e >= 0) {
                    found = e
                    break
                }
                q++
            }
            if (found < 0) return false
            if (last) return true
            position = found
            star = segEnd
        }
    }

    /** Matches `pool[from until to]` (no '*') at [p] of the url; returns where it ends there, -1 if it does not */
    private fun matchSegment(url: String, p: Int, pool: ByteArray, from: Int, to: Int): Int {
        var u = p
        val length = url.length
        for (k in from until to) {
            val c = pool[k]
            if (c == CARET) {
                //a separator, or the end of the url
                if (u < length) {
                    if (!Hashing.isSeparator(url[u])) return -1
                    u++
                }
            } else {
                if (u >= length || url[u].code != c.toInt()) return -1
                u++
            }
        }
        return u
    }

    private fun indexOfStar(pool: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (pool[i] == STAR) return i
        return to
    }
}
