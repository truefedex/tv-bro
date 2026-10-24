package com.phlox.tvwebbrowser.adblock.engine

/** The hashes and character classes the compiler and the engine must agree on */
internal object Hashing {
    private const val FNV32_OFFSET = -0x7ee3623b // 0x811C9DC5
    private const val FNV32_PRIME = 0x01000193
    private const val FNV64_OFFSET = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
    private const val FNV64_PRIME = 0x100000001b3L

    /** The hash of a token (the index of filters by token) */
    fun token(s: String, from: Int, to: Int): Int {
        var h = FNV32_OFFSET
        for (i in from until to) h = (h xor s[i].code) * FNV32_PRIME
        return h xor (h ushr 15)
    }

    /** The hash of a host or domain: 64 bits, so that two domains practically never share one */
    fun domain(s: String, from: Int = 0, to: Int = s.length): Long {
        var h = FNV64_OFFSET
        for (i in from until to) h = (h xor s[i].code.toLong()) * FNV64_PRIME
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
