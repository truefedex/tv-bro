package com.phlox.tvwebbrowser.adblock.engine

import com.phlox.tvwebbrowser.adblock.ResourceType

/** The bits of the flags of a compiled request filter; the low bits are its [ResourceType]s */
internal object FilterFlags {
    const val TYPES = ResourceType.ALL
    const val THIRD_PARTY = 1 shl 12
    const val FIRST_PARTY = 1 shl 13
    const val IMPORTANT = 1 shl 14
    const val MATCH_CASE = 1 shl 15
    const val ANCHOR_HOST = 1 shl 16
    const val ANCHOR_START = 1 shl 17
    const val ANCHOR_END = 1 shl 18
    const val REGEX = 1 shl 19
    const val EXCEPTION = 1 shl 20
    const val ELEMHIDE = 1 shl 21
    const val GENERICHIDE = 1 shl 22
    /** `||host^`: the pattern is the host, found through the host index */
    const val HOST_ONLY = 1 shl 23
    /** The pattern has a `*`: without one the matcher need not look for its segments */
    const val WILDCARD = 1 shl 24
    /**
     * Bits 25 to 31: where the token the filter is indexed by starts in its pattern, so that the engine
     * checks the pattern only where the url has that token; [TOKEN_OFFSET_UNKNOWN] when a `*` comes before
     * it, it is further than 126, or the filter is not indexed by a token
     */
    const val TOKEN_OFFSET_SHIFT = 25
    const val TOKEN_OFFSET_UNKNOWN = 0x7F

    fun tokenOffset(flags: Int) = (flags ushr TOKEN_OFFSET_SHIFT) and TOKEN_OFFSET_UNKNOWN
}

/** The tables of filters */
internal object Tables {
    /** Exceptions for pages: `$document`, `$elemhide`, `$generichide` */
    const val PAGE = 0
    /** Blocking filters with `$important`, asked only when an exception matched (they are in [BLOCK] too) */
    const val IMPORTANT = 1
    /** All blocking filters */
    const val BLOCK = 2
    /** Exceptions for requests */
    const val ALLOW = 3
    const val COUNT = 4
}

/** Growable arrays of primitives for the compiler */
internal class IntList(capacity: Int = 1024) {
    var array = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(size * 2)
        array[size++] = value
    }

    operator fun get(i: Int) = array[i]
    fun toArray(): IntArray = array.copyOf(size)
}

internal class LongList(capacity: Int = 1024) {
    private var array = LongArray(capacity)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == array.size) array = array.copyOf(size * 2)
        array[size++] = value
    }

    fun toArray(): LongArray = array.copyOf(size)
}

internal class ByteList(capacity: Int = 64 * 1024) {
    private var array = ByteArray(capacity)
    var size = 0
        private set

    fun add(value: Byte) {
        if (size == array.size) array = array.copyOf(size * 2)
        array[size++] = value
    }

    fun toArray(): ByteArray = array.copyOf(size)
}
