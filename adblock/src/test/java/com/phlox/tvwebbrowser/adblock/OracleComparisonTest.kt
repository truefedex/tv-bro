package com.phlox.tvwebbrowser.adblock

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Compares the decisions of the engine with those of another ad blocker (the oracle) for the same requests
 * and lists: `-PadblockLists=<dir>` (the `*.txt` lists the oracle used) and `-PadblockOracle=<file>`, lines of
 * `url<TAB>type<TAB>document url<TAB>block|allow|none<TAB>its filter` (see `tools/adblock-corpus/oracle.mjs`).
 * Prints the requests decided otherwise, with the filters of both. Skipped without them; neither the lists
 * nor the decisions are in the repository.
 */
class OracleComparisonTest {
    private val lists = System.getProperty("adblock.lists")?.let(::File)
    private val oracle = System.getProperty("adblock.oracle")?.let(::File)

    @Test
    fun decisionsAgreeWithTheOracle() {
        assumeTrue("no -PadblockLists and -PadblockOracle", lists?.isDirectory == true && oracle?.isFile == true)
        val compiler = FilterCompiler(keepTexts = true)
        for (list in lists!!.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }) {
            list.bufferedReader().useLines { compiler.addList(it) }
        }
        val engine = compiler.build(TestPsl.psl)

        var total = 0
        val differences = LinkedHashMap<String, MutableList<String>>()
        for (line in oracle!!.readLines()) {
            val parts = line.split('\t')
            if (parts.size < 4) continue
            val (url, type, document, expected) = parts
            val page = document.ifEmpty { url }
            val id = engine.matchFilter(url, hostOf(url), page, hostOf(page), ResourceType.fromWebExtension(type))
            val result = when {
                id == FilterEngine.NO_FILTER -> "none"
                id == FilterEngine.PAGE_EXCEPTION -> "allow"
                engine.filterText(id)!!.startsWith("@@") -> "allow"
                else -> "block"
            }
            total++
            //an exception without a block is "none" for the engine
            if (result == expected || (expected == "allow" && result == "none")) continue
            val key = "oracle $expected, engine $result"
            differences.getOrPut(key) { ArrayList() } +=
                "$type $url <- $page | oracle: ${parts.getOrNull(4)} | engine: ${engine.filterText(id)}"
        }
        val different = differences.values.sumOf { it.size }
        println("requests $total, decided otherwise $different")
        for ((key, lines) in differences) {
            println("== $key: ${lines.size}")
            lines.take(40).forEach { println("  " + it.take(400)) }
        }
        assertTrue("decided otherwise: $different of $total", different * 200 <= total)
    }

    /** "" for urls without a host (about:blank frames: the corpus does not know their page, as the oracle) */
    private fun hostOf(url: String) =
        if (!url.contains("://")) "" else url.substringAfter("://").takeWhile { it !in "/?#:" }.substringAfter('@').lowercase()
}
