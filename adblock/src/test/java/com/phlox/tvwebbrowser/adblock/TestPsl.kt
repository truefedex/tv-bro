package com.phlox.tvwebbrowser.adblock

import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import java.io.File
import java.net.URI

/** The Public Suffix List of the module's asset, and helpers for the tests of engines */
object TestPsl {
    val moduleDir = File(System.getProperty("adblock.moduleDir") ?: ".")
    val psl: PublicSuffixList by lazy {
        PublicSuffixList.load(File(moduleDir, "src/main/assets/adblock/psl.txt").readBytes())
    }

    fun compile(vararg lines: String): FilterEngine {
        val compiler = FilterCompiler(keepTexts = true)
        compiler.addList(lines.asSequence())
        return compiler.build(psl)
    }

    fun host(url: String): String = URI(url).host ?: ""
}

/** [FilterEngine.match] for urls, the page being https://example.com/ unless given */
fun FilterEngine.check(url: String, page: String? = "https://example.com/", types: Int = ResourceType.SCRIPT): Int =
    match(url, TestPsl.host(url), page, page?.let(TestPsl::host), types)
