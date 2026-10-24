package com.phlox.tvwebbrowser.adblock.engine

/** The hashes and character classes the compiler and the engine must agree on */
internal object Hashing {
    private const val FNV32_OFFSET = -0x7ee3623b // 0x811C9DC5
    const val FNV32_PRIME = 0x01000193
    private const val FNV64_OFFSET = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
    private const val FNV64_PRIME = 0x100000001b3L

    /**
     * Only the first characters of a token make its hash: long tokens of urls are mostly random ids, whose
     * whole hashing would cost more than the few longer tokens of filters that then share a hash
     */
    const val TOKEN_HASH_CHARS = 16

    /** The hash of a token (the index of filters by token), of its first [TOKEN_HASH_CHARS] characters */
    fun token(s: String, from: Int, to: Int): Int {
        var h = FNV32_OFFSET
        for (i in from until minOf(to, from + TOKEN_HASH_CHARS)) h = (h xor s[i].code) * FNV32_PRIME
        return finishToken(h)
    }

    const val TOKEN_START = FNV32_OFFSET

    /** One more character of a hash started with [TOKEN_START] (the engine hashes while it tokenizes) */
    @Suppress("NOTHING_TO_INLINE")
    inline fun tokenStep(h: Int, c: Char): Int = (h xor c.code) * FNV32_PRIME

    fun finishToken(h: Int): Int = h xor (h ushr 15)

    /**
     * The 32-bit hash of a host (the index of host filters; the engine compares the host itself), over its
     * characters from the last one: one pass from the end gives the hashes of all suffixes of a host
     */
    fun host(s: String, from: Int, to: Int): Int {
        var h = FNV32_OFFSET
        for (i in to - 1 downTo from) h = (h xor s[i].code) * FNV32_PRIME
        return finishToken(h)
    }

    /**
     * The [host] in lowercase ASCII without a copy when it has no uppercase letter (as urls mostly do);
     * other characters stay as they are
     */
    fun asciiLowercase(text: String): String {
        var i = 0
        val length = text.length
        while (i < length && text[i] !in 'A'..'Z') i++
        if (i == length) return text
        val chars = text.toCharArray()
        while (i < length) {
            val c = chars[i]
            if (c in 'A'..'Z') chars[i] = c + 32
            i++
        }
        return String(chars)
    }

    /** The hash of a host or domain: 64 bits, so that two domains practically never share one */
    fun domain(s: String, from: Int = 0, to: Int = s.length): Long {
        var h = FNV64_OFFSET
        for (i in from until to) h = (h xor s[i].code.toLong()) * FNV64_PRIME
        return h xor (h ushr 29)
    }

    /** The key of a class (`prefix` '.') or an id ('#') of element hiding: [name] from [from] until [to] */
    fun nameKey(prefix: Char, name: String, from: Int = 0, to: Int = name.length): Long {
        var h = (FNV64_OFFSET xor prefix.code.toLong()) * FNV64_PRIME
        for (i in from until to) h = (h xor name[i].code.toLong()) * FNV64_PRIME
        return h xor (h ushr 29)
    }

    /** Tokens are the longest runs of these characters (in lowercase urls) */
    fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

    /** What `^` of a pattern matches: anything but a letter, a digit, `_ - . %` and non-ASCII (as Adblock Plus) */
    fun isSeparator(c: Char): Boolean {
        val code = c.code
        if (code >= 0x80) return false
        return !(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '%')
    }
}
