package com.phlox.tvwebbrowser.adblock

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Compiles the real lists of `-PadblockLists=<dir>` (`*.txt`) and checks the requests of `requests.tsv` there
 * (`url<TAB>type<TAB>document url`, see tools/adblock-corpus): prints the times, the sizes and what was blocked.
 * Skipped without the directory. For the numbers of a TV, AdblockBenchActivity of the debug app does the same.
 */
class EngineBenchmarkTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dir = System.getProperty("adblock.lists")?.let(::File)

    private class Request(val url: String, val host: String, val page: String?, val pageHost: String?, val types: Int)

    @Test
    fun benchmark() {
        assumeTrue("no -PadblockLists", dir != null && dir.isDirectory)
        val lists = dir!!.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        assumeTrue(lists.isNotEmpty())
        val psl = TestPsl.psl

        var start = System.nanoTime()
        val compiler = FilterCompiler(keepTexts = true)
        for (list in lists) list.bufferedReader().useLines { compiler.addList(it) }
        val engine = compiler.build(psl)
        val compileMs = (System.nanoTime() - start) / 1e6
        val file = File(temp.root, "engine.bin")
        engine.writeTo(file)
        start = System.nanoTime()
        val loaded = FilterEngine.load(file, psl)
        val loadMs = (System.nanoTime() - start) / 1e6
        println("lists: ${lists.joinToString { it.name }}")
        println(String.format(Locale.US, "compile %.0f ms, file %d KB, load %.1f ms, arrays %d KB, filters %d, regex %d, unkeyed %s",
            compileMs, file.length() / 1024, loadMs, loaded.memoryBytes / 1024, loaded.filterCount, loaded.regexCount,
            loaded.unkeyedFilterCounts.toList()))

        val requestsFile = File(dir, "requests.tsv")
        assumeTrue("no requests.tsv", requestsFile.exists())
        val requests = requestsFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val host = hostOf(parts[0]) ?: return@mapNotNull null
            val page = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            Request(parts[0], host, page, page?.let(::hostOf), ResourceType.fromWebExtension(parts.getOrNull(1)))
        }
        val results = IntArray(requests.size)
        //a pass for the JIT, then the measured ones
        repeat(2) { for ((i, r) in requests.withIndex()) results[i] = loaded.match(r.url, r.host, r.page, r.pageHost, r.types) }
        val passes = 5
        val times = LongArray(requests.size * passes)
        var k = 0
        repeat(passes) {
            for (r in requests) {
                val t = System.nanoTime()
                loaded.match(r.url, r.host, r.page, r.pageHost, r.types)
                times[k++] = System.nanoTime() - t
            }
        }
        times.sort()
        fun us(p: Double) = times[minOf(times.size - 1, (times.size * p).toInt())] / 1000.0
        println(String.format(Locale.US, "check (µs): p50 %.2f, p90 %.2f, p99 %.2f, max %.1f, mean %.2f; requests %d, blocked %d, allowed %d",
            us(0.5), us(0.9), us(0.99), times.last() / 1000.0, times.average() / 1000.0, requests.size,
            results.count { it == MatchResult.BLOCK }, results.count { it == MatchResult.ALLOW }))

        //element hiding of the pages of the corpus: the CSS of a page, and its generic selectors for some classes
        val pages = requests.mapNotNull { r -> if (r.page?.startsWith("http") == true && r.pageHost != null) r.page to r.pageHost else null }.distinct()
        val classes = listOf("ad", "ads", "banner", "ad-container", "sidebar", "content", "header", "footer", "promo",
            "advert", "sponsored", "article", "nav", "menu", "adsbygoogle", "google-ad", "ad-slot", "teaser")
        repeat(2) { for ((url, host) in pages) loaded.hidingStyles(url, host) }
        var cssChars = 0L
        var start2 = System.nanoTime()
        for ((url, host) in pages) cssChars += loaded.hidingStyles(url, host).css.length
        val pageUs = (System.nanoTime() - start2) / 1000.0 / pages.size
        start2 = System.nanoTime()
        for ((url, host) in pages) loaded.genericHidingStyles(url, host, classes, listOf("ad", "top-ad"))
        val genericUs = (System.nanoTime() - start2) / 1000.0 / pages.size
        println(String.format(Locale.US, "hiding: %d selectors; %d pages: styles %.1f µs (css %d chars on average), " +
                "generic of %d names %.1f µs", loaded.hidingSelectorCount, pages.size, pageUs, cssChars / pages.size,
            classes.size + 2, genericUs))

        //the filters that block the most, to see that nothing blocks everything
        val byFilter = HashMap<String, Int>()
        for (r in requests) {
            val id = engine.matchFilter(r.url, r.host, r.page, r.pageHost, r.types)
            if (id != FilterEngine.NO_FILTER) byFilter.merge(engine.filterText(id) ?: "?", 1, Int::plus)
        }
        println("top filters: " + byFilter.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key} ${it.value}" })
        //the slowest requests
        val slow = requests.map { r ->
            val t = System.nanoTime()
            repeat(20) { loaded.match(r.url, r.host, r.page, r.pageHost, r.types) }
            (System.nanoTime() - t) / 20 to r.url
        }.sortedByDescending { it.first }.take(5)
        println("slowest: " + slow.joinToString { "${it.first / 1000} µs ${it.second.take(120)}" })
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }
}
