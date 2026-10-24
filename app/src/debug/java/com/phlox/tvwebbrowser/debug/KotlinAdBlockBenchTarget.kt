package com.phlox.tvwebbrowser.debug

import android.content.res.AssetManager
import android.net.Uri
import com.phlox.tvwebbrowser.adblock.FilterCompiler
import com.phlox.tvwebbrowser.adblock.FilterEngine
import com.phlox.tvwebbrowser.adblock.MatchResult
import com.phlox.tvwebbrowser.adblock.ResourceType
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
import java.io.File

/** The ad blocker of the :adblock module; the requests carry types of WebExtensions (requests.tsv) */
class KotlinAdBlockBenchTarget(private val assets: AssetManager) : AdblockBenchTarget {
    override val name = "adblock kotlin"
    private val psl by lazy { PublicSuffixList.load(assets.open("adblock/psl.txt").use { it.readBytes() }) }
    private var engine: FilterEngine? = null

    override fun build(lists: List<File>, dir: File) {
        val compiler = FilterCompiler()
        for (list in lists) list.bufferedReader().useLines { compiler.addList(it) }
        val engine = compiler.build(psl).also { engine = it }
        engine.writeTo(File(dir, "engine.bin"))
    }

    override fun load(dir: File) {
        engine = FilterEngine.load(File(dir, "engine.bin"), psl)
    }

    override fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean {
        val engine = engine!!
        return engine.match(url.toString(), url.host ?: "", baseUri.toString(), baseUri.host,
            ResourceType.fromWebExtension(type)) == MatchResult.BLOCK
    }

    override fun release() {
        engine = null
    }

    override fun describe(): String? = engine?.let {
        "filters ${it.filterCount}, regex ${it.regexCount}, arrays ${it.memoryBytes / 1024} KB, unkeyed ${it.unkeyedFilterCounts.toList()}"
    }
}
