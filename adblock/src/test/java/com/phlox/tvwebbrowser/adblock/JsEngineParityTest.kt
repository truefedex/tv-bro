package com.phlox.tvwebbrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The matcher of GeckoView's extension (`src/main/js/adblock_engine.js`) must decide as [FilterEngine]: this
 * runs it under node (`src/test/js/match_requests.js`, skipped without node) on the same file and requests.
 * Always on filters of every kind; with `-PadblockLists=<dir>` and its `requests.tsv` also on the real lists.
 */
class JsEngineParityTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val moduleDir = TestPsl.moduleDir
    private val listsDir = System.getProperty("adblock.lists")?.let(::File)

    private class Request(val url: String, val host: String, val page: String?, val pageHost: String?, val types: Int)

    @Test
    fun filtersOfEveryKind() {
        assumeTrue("no node", nodeAvailable)
        val engine = TestPsl.compile(*FILTERS)
        val pages = listOf("https://page.com/", "https://sub.page.com/x", "https://trusted.page/a", "https://other.org/",
            "https://www.ads.example.net/", "about:blank", null)
        val types = listOf(ResourceType.SCRIPT, ResourceType.IMAGE, ResourceType.PING, ResourceType.SUBDOCUMENT,
            ResourceType.DOCUMENT, ResourceType.XHR, ResourceType.SCRIPT or ResourceType.XHR or ResourceType.OTHER)
        val requests = URLS.flatMap { url ->
            pages.flatMap { page ->
                types.map { Request(url, host(url) ?: "", page, page?.let(::host), it) }
            }
        }
        val (differences, _) = compare(engine, requests)
        assertEquals("different decisions:\n" + differences.joinToString("\n"), 0, differences.size)
    }

    @Test
    fun theDecisionOfTheApp() {
        assumeTrue("no node", nodeAvailable)
        val engine = TestPsl.compile("||ads.example.net^", "*\$ping,third-party", "||bad.example^\$document")
        //[url, type, document, top] and what AppEngineAdBlocker.shouldBlock gives for them
        val cases = listOf(
            listOf("https://ads.example.net/a.js", "script", "https://site.com/", "https://site.com/") to 1,
            //the site setting of the page of the tab applies, also to its frames of other sites
            listOf("https://ads.example.net/a.js", "script", "https://off.com/", "https://off.com/") to 0,
            listOf("https://ads.example.net/a.js", "script", "https://frame.com/", "https://off.com/") to 0,
            listOf("https://ads.example.net/a.js", "script", "https://off.com/", "https://site.com/") to 1,
            //a frame without an address is of the page of the tab
            listOf("https://ping.example.org/collect", "ping", "about:blank", "https://site.com/") to 1,
            listOf("https://cdn.site.com/collect", "ping", "about:blank", "https://site.com/") to 0,
            listOf("https://cdn.site.com/collect", "ping", "about:srcdoc", "https://site.com/") to 0,
            //nothing on the home page
            listOf("https://ads.example.net/a.js", "script", HOME_PAGE + "index.html", HOME_PAGE + "index.html") to 0,
            //pages are blocked only by document filters, and their own setting applies
            listOf("https://bad.example/", "main_frame", null, null) to 1,
            listOf("https://ads.example.net/", "main_frame", null, null) to 0,
            listOf("https://bad.example/", "main_frame", "https://off.com/", "https://off.com/") to 1,
            //no page known: the global setting
            listOf("https://ads.example.net/a.js", "script", null, null) to 1,
            listOf("data:text/plain,ads", "script", "https://site.com/", "https://site.com/") to 0,
            listOf("https://ads.example.net/a.js", "script", "https://on.org/", "https://on.org/") to 1,
            //hosts as Uri.getHost gives them
            listOf("https://user:pw@ads.example.net:8443/a.js", "script", "https://site.com/", "https://site.com/") to 1,
            listOf("https://ads.example.net/a.js", "script", "https://off.com:8080/x", "https://off.com:8080/x") to 0,
            listOf("https://ads.example.net/a.js", "script", "blob:https://off.com/1-2", "https://off.com/") to 0,
            listOf("https://cdn.site.com/collect", "ping", "blob:https://site.com/1-2", "https://site.com/") to 0,
            listOf("https://ping.example.org/collect", "ping", "blob:https://site.com/1-2", "https://site.com/") to 1,
        )
        val engineFile = File(temp.root, "engine.bin").apply { writeBytes(engine.requestFiltersFile()) }
        val json = buildString {
            append("""{"settings":{"enabled":true,"hosts":{"off.com":false,"on.org":true},"homePageUrl":"$HOME_PAGE"},""")
            append(""""requests":[""")
            append(cases.joinToString(",") { (request, _) ->
                request.joinToString(",", "[", "]") { if (it == null) "null" else "\"$it\"" }
            })
            append("]}")
        }
        val input = File(temp.root, "cases.json").apply { writeText(json) }
        val decisions = runNode("decide", engineFile, input)
        assertEquals(cases.map { it.second.toString() }, decisions)
    }

    @Test
    fun theRealLists() {
        assumeTrue("no node", nodeAvailable)
        assumeTrue("no -PadblockLists", listsDir != null && listsDir.isDirectory)
        val lists = listsDir!!.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        val requestsFile = File(listsDir, "requests.tsv")
        assumeTrue("no lists or requests", lists.isNotEmpty() && requestsFile.exists())
        val compiler = FilterCompiler(keepTexts = true)
        for (list in lists) list.bufferedReader().useLines { compiler.addList(it) }
        val engine = compiler.build(TestPsl.psl)
        val requests = requestsFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val host = host(parts[0]) ?: return@mapNotNull null
            val page = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            Request(parts[0], host, page, page?.let(::host), ResourceType.fromWebExtension(parts.getOrNull(1)))
        }
        val (differences, time) = compare(engine, requests)
        println("${requests.size} requests, ${differences.size} different decisions, $time")
        differences.take(30).forEach(::println)
        assertEquals("different decisions", 0, differences.size)
    }

    /** The requests the two engines decide differently on (with the filter of Kotlin), and the time line of node */
    private fun compare(engine: FilterEngine, requests: List<Request>): Pair<List<String>, String> {
        val engineFile = File(temp.root, "engine.bin").apply { writeBytes(engine.requestFiltersFile()) }
        val input = File(temp.root, "requests.tsv")
        input.bufferedWriter().use { out ->
            for (r in requests) {
                out.write("${r.url}\t${r.host}\t${r.page ?: ""}\t${r.pageHost ?: ""}\t${r.types}\n")
            }
        }
        val output = runNode("match", engineFile, input)
        assertEquals("node gave no decision for every request", requests.size + 1, output.size)
        val differences = ArrayList<String>()
        for ((i, r) in requests.withIndex()) {
            val kotlin = engine.match(r.url, r.host, r.page, r.pageHost, r.types)
            val js = output[i].toInt()
            if (kotlin != js) {
                val filter = engine.filterText(engine.matchFilter(r.url, r.host, r.page, r.pageHost, r.types))
                differences += "kotlin $kotlin js $js: ${r.url} page ${r.page} types ${r.types} ($filter)"
            }
        }
        return differences to output.last()
    }

    private fun runNode(mode: String, engineFile: File, input: File): List<String> {
        val script = File(moduleDir, "src/test/js/match_requests.js")
        val psl = File(moduleDir, "src/main/assets/adblock/psl.txt")
        val process = ProcessBuilder("node", script.path, mode, engineFile.path, psl.path, input.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        check(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) { output.joinToString("\n") }
        return output
    }

    private fun host(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val HOME_PAGE = "https://tvbro.phlox.dev/appcontent/home/"

        private val nodeAvailable: Boolean by lazy {
            try {
                ProcessBuilder("node", "--version").start().waitFor(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                false
            }
        }

        private val FILTERS = arrayOf(
            "||ads.example.net^",
            "||tracker.com^\$third-party",
            "||first.example^\$~third-party",
            "||cdn.site.org/ads/*\$script",
            "/banner/*/img^",
            "|https://exact.example/start",
            ".gif|",
            "||img.example.net^\$image",
            "@@||img.example.net/allowed^",
            "||important.example^\$important",
            "@@||important.example^",
            "@@||trusted.page^\$document",
            "||paged.example^\$domain=page.com|~sub.page.com",
            "||nodomain.example^\$domain=~page.com",
            "/regex[0-9]+\\/ad/",
            "/AdPath/\$match-case",
            "/\\/CaseRegex\\d/\$match-case",
            "*\$ping,third-party",
            "&ad_type=",
            "-ad-banner.",
            "||socialwidget.example^\$subdocument",
            "@@||page.com^\$elemhide",
            "||bad.example^\$document",
            "/ads?",
            "||xn--e1afmkfd.xn--p1ai^",
            "|http://*.tracker.net/pixel",
            "^adserver^",
            "@@/ads?keep=",
            "||other.org/ad.js\$domain=other.org",
            "||w.example^\$xmlhttprequest,~third-party",
        )

        private val URLS = listOf(
            "https://ads.example.net/a.js", "https://www.ads.example.net/b.js", "https://ADS.Example.NET/C.js",
            "https://notads.example.net/a.js", "https://tracker.com/p", "https://sub.tracker.com/p",
            "https://first.example/x", "https://cdn.site.org/ads/1.js", "https://cdn.site.org/ads/1.png",
            "https://host.org/banner/x/y/img?", "https://host.org/banner/img", "https://exact.example/start/here",
            "http://exact.example/start", "https://host.org/a.gif", "https://host.org/a.gif?x",
            "https://img.example.net/1.png", "https://img.example.net/allowed/1.png", "https://important.example/x",
            "https://trusted.page/ads?x", "https://paged.example/x", "https://nodomain.example/x",
            "https://host.org/regex123/ad", "https://host.org/AdPath/x", "https://host.org/adpath/x",
            "https://host.org/CaseRegex1", "https://host.org/caseregex1", "https://ping.org/collect",
            "https://host.org/x?a=1&ad_type=2", "https://host.org/top-ad-banner.png", "https://socialwidget.example/w",
            "https://page.com/ads?x=1", "https://page.com/ads?keep=1", "https://bad.example/",
            "https://xn--e1afmkfd.xn--p1ai/x", "http://a.tracker.net/pixel", "https://host.org/adserver/x",
            "https://host.org/x/adserver", "https://other.org/ad.js", "https://w.example/api",
            "https://host.org/İstanbul/ads?x", "https://host.org/" + "a".repeat(5000) + "/ads?",
            "https://host.org/path?q=" + "x%2F".repeat(700) + "ad_type=",
        )
    }
}
