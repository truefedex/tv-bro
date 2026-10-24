package com.phlox.tvwebbrowser.adblock.engine

/**
 * An index of filters: the filters found by the 32-bit hash of a host ([hostKeys]) or of a token
 * ([tokenKeys]), plus the bucket of filters without either ([noTokenBucket]), which every request checks.
 *
 * The keys are open addressing tables (linear probing, about 1.5 slots per key). A slot holds 0 when empty,
 * `-(id + 1)` for a key of a single filter (most keys), or `bucket + 1` for a key of several: bucket b is
 * `bucketFilters[bucketStarts[b] until bucketStarts[b + 1]]`.
 * Host keys are only hashes: the engine compares the host of the filter itself.
 */
internal class IndexTable(
    val hostKeys: IntArray,
    val hostSlots: IntArray,
    val tokenKeys: IntArray,
    val tokenSlots: IntArray,
    val bucketStarts: IntArray,
    val bucketFilters: IntArray,
    val noTokenBucket: Int,
    /** The number of filters in the table */
    val filterCount: Int,
) {
    /** The slot value of the host key [hash] (see the class), 0 if none */
    fun hostSlot(hash: Int): Int = find(hostKeys, hostSlots, hash)

    fun tokenSlot(hash: Int): Int = find(tokenKeys, tokenSlots, hash)

    private fun find(keys: IntArray, slots: IntArray, hash: Int): Int {
        val size = slots.size
        if (size == 0) return 0
        var i = (hash and Int.MAX_VALUE) % size
        while (true) {
            val value = slots[i]
            if (value == 0 || keys[i] == hash) return value
            i++
            if (i == size) i = 0
        }
    }

    val noTokenSize: Int get() = if (noTokenBucket < 0) 0 else bucketStarts[noTokenBucket + 1] - bucketStarts[noTokenBucket]

    fun arrays(): List<IntArray> = listOf(hostKeys, hostSlots, tokenKeys, tokenSlots, bucketStarts, bucketFilters,
        intArrayOf(noTokenBucket, filterCount))

    companion object {
        /** From the arrays of [arrays], in their order */
        fun of(arrays: List<Any>): IndexTable {
            val info = arrays[6] as IntArray
            return IndexTable(arrays[0] as IntArray, arrays[1] as IntArray, arrays[2] as IntArray, arrays[3] as IntArray,
                arrays[4] as IntArray, arrays[5] as IntArray, info[0], info[1])
        }

        /**
         * Builds the table of the filters [hostKeyed] (filter ids by the hash of their host), [tokenKeyed] (by the hash
         * of their token) and [unkeyed]
         */
        fun build(hostKeyed: Map<Int, IntArray>, tokenKeyed: Map<Int, IntArray>, unkeyed: IntArray): IndexTable {
            val starts = IntList(64)
            val filters = IntList(256)
            fun addBucket(ids: IntArray): Int {
                starts.add(filters.size)
                for (id in ids) filters.add(id)
                return starts.size - 1
            }
            fun slotValue(ids: IntArray) = if (ids.size == 1) -(ids[0] + 1) else addBucket(ids) + 1

            val (hostKeys, hostSlots) = fill(hostKeyed, ::slotValue)
            val (tokenKeys, tokenSlots) = fill(tokenKeyed, ::slotValue)
            val noTokenBucket = if (unkeyed.isEmpty()) -1 else addBucket(unkeyed)
            starts.add(filters.size)
            val count = hostKeyed.values.sumOf { it.size } + tokenKeyed.values.sumOf { it.size } + unkeyed.size
            return IndexTable(hostKeys, hostSlots, tokenKeys, tokenSlots, starts.toArray(), filters.toArray(),
                noTokenBucket, count)
        }

        private fun fill(keyed: Map<Int, IntArray>, slotValue: (IntArray) -> Int): Pair<IntArray, IntArray> {
            if (keyed.isEmpty()) return IntArray(0) to IntArray(0)
            val size = keyed.size + keyed.size / 2 + 1
            val keys = IntArray(size)
            val slots = IntArray(size)
            //sorted, so that the same filters give the same file
            for (hash in keyed.keys.sorted()) {
                var i = (hash and Int.MAX_VALUE) % size
                while (slots[i] != 0) {
                    i++
                    if (i == size) i = 0
                }
                slots[i] = slotValue(keyed.getValue(hash))
                keys[i] = hash
            }
            return keys to slots
        }
    }
}
