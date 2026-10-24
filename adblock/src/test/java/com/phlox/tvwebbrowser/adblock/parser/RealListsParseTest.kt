package com.phlox.tvwebbrowser.adblock.parser

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parses real filter lists (not in the repository, see their licenses): the `*.txt` files of the directory
 * given with `-PadblockLists=<dir>`. Prints what was skipped; skipped otherwise.
 */
class RealListsParseTest {
    private val dir = System.getProperty("adblock.lists")?.let(::File)

    @Test
    fun parsesRealLists() {
        assumeTrue("no -PadblockLists", dir != null && dir.isDirectory)
        val lists = dir!!.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        assumeTrue("no *.txt lists in $dir", lists.isNotEmpty())
        for (list in lists) {
            //a few examples of each reason
            val examples = LinkedHashMap<String, MutableList<String>>()
            val parser = FilterParser { line, reason ->
                val forReason = examples.getOrPut(reason) { ArrayList() }
                if (forReason.size < 5) forReason += line
            }
            val start = System.nanoTime()
            val stats = list.bufferedReader().useLines { lines -> parser.parseList(lines) {} }
            val ms = (System.nanoTime() - start) / 1_000_000
            println("${list.name}: $ms ms, $stats")
            for ((reason, lines) in examples) println("  $reason: " + lines.joinToString("  "))
            val filters = stats.network + stats.cosmetic
            assertTrue("${list.name}: no filters", filters > 0)
            //most of a list of this syntax is supported
            assertTrue("${list.name}: skipped ${stats.skippedTotal} of ${filters + stats.skippedTotal}",
                stats.skippedTotal < (filters + stats.skippedTotal) / 5)
        }
    }
}
