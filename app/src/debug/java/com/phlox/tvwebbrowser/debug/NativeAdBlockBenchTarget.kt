package com.phlox.tvwebbrowser.debug

import android.net.Uri
import com.brave.adblock.AdBlockClient
import com.phlox.tvwebbrowser.activity.main.AdblockModel
import java.io.File

/** The native ad-block library the app uses now, as AdblockModel uses it (goes away with the library) */
class NativeAdBlockBenchTarget : AdblockBenchTarget {
    override val name = "ad-block native"
    private var client: AdBlockClient? = null

    override fun build(lists: List<File>, dir: File) {
        val client = AdBlockClient().also { client = it }
        check(client.parse(lists.joinToString("\n") { it.readText() })) { "parse failed" }
        check(client.serialize(File(dir, "adblock_ser.dat").absolutePath)) { "serialize failed" }
    }

    override fun load(dir: File) {
        val client = AdBlockClient().also { client = it }
        check(client.deserialize(File(dir, "adblock_ser.dat").absolutePath)) { "deserialize failed" }
    }

    override fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean =
        AdblockModel.isAd(client!!, url, type, baseUri)

    override fun release() {
        //finalize() of the client frees it (deinit); calling deinit too frees it twice
        client = null
    }
}
