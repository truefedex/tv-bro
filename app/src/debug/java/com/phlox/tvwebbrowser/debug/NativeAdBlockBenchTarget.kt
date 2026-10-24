package com.phlox.tvwebbrowser.debug

import android.net.Uri
import com.brave.adblock.AdBlockClient
import com.brave.adblock.AdBlockClient.FilterOption
import com.brave.adblock.Utils
import java.io.File

/** The native ad-block library the app used, checked as its AdblockModel did (goes away with the library) */
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
        NativeMatching.isAd(client!!, url, type, baseUri)

    override fun release() {
        //finalize() of the client frees it (deinit); calling deinit too frees it twice
        client = null
    }
}

/** The check of the removed AdblockModel */
private object NativeMatching {
    fun isAd(client: AdBlockClient, url: Uri, type: String?, baseUri: Uri): Boolean {
        val baseHost = baseUri.host
        val filterOption = try {
            mapRequestToFilterOption(url, type)
        } catch (e: Exception) {
            return false
        }
        val result = try {
            baseHost != null && client.matches(url.toString(), filterOption, baseHost)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        return result
    }

    private fun mapRequestToFilterOption(url: Uri?, type: String?): FilterOption? {
        if (type != null) {
            if (type == "image" || type.contains("image/")) {
                return FilterOption.IMAGE
            }
            if (type == "style" || type.contains("/css")) {
                return FilterOption.CSS
            }
            if (type == "script" || type.contains("javascript")) {
                return FilterOption.SCRIPT
            }
            if (type.contains("video/")) {
                return FilterOption.OBJECT
            }
        }
        if (url != null) {
            if (Utils.uriHasExtension(url, "css")) {
                return FilterOption.CSS
            }
            if (Utils.uriHasExtension(url, "js")) {
                return FilterOption.SCRIPT
            }
            if (Utils.uriHasExtension(
                    url,
                    "png",
                    "jpg",
                    "jpeg",
                    "webp",
                    "svg",
                    "gif",
                    "bmp",
                    "tiff"
                )
            ) {
                return FilterOption.IMAGE
            }
            if (Utils.uriHasExtension(url, "mp4", "mov", "avi")) {
                return FilterOption.OBJECT
            }
        }
        return FilterOption.UNKNOWN
    }
}
