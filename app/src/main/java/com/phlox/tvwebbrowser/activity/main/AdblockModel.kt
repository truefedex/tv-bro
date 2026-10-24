package com.phlox.tvwebbrowser.activity.main

import android.net.Uri
import android.widget.Toast
import com.brave.adblock.AdBlockClient
import com.brave.adblock.AdBlockClient.FilterOption
import com.brave.adblock.Utils
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.browser.AdBlocker
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.*

class AdblockModel : ActiveModel(), AdBlocker {
    private var client: AdBlockClient? = null
    val clientLoading = ObservableValue(false)
    val config = AppContext.provideConfig()

    init {
        loadAdBlockList(false)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun loadAdBlockList(forceReload: Boolean) = modelScope.launch {
        if (clientLoading.value) return@launch
        val checkDate = Calendar.getInstance()
        checkDate.timeInMillis = config.adBlockListLastUpdate
        checkDate.add(Calendar.MINUTE, AUTO_UPDATE_INTERVAL_MINUTES)
        val now = Calendar.getInstance()
        val needUpdate = forceReload || checkDate.before(now)
        clientLoading.value = true
        val client = AdBlockClient()
        var success = false
        withContext(Dispatchers.IO) ioContext@ {
            val serializedFile = File(AppContext.get().filesDir, SERIALIZED_LIST_FILE)
            if ((!needUpdate) && serializedFile.exists() && client.deserialize(serializedFile.absolutePath)) {
                success = true
                return@ioContext
            }
            try {
                val easyList = URL(config.adBlockListURL.value).openConnection().inputStream.bufferedReader()
                  .use { it.readText() }
                success = client.parse(easyList)
                client.serialize(serializedFile.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        this@AdblockModel.client = client
        config.adBlockListLastUpdate = now.timeInMillis
        if (!success) {
            Toast.makeText(AppContext.get(), "Error loading ad-blocker list", Toast.LENGTH_SHORT).show()
        }
        clientLoading.value = false
    }

    override fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean {
        val client = client ?: return false
        return isAd(client, url, type, baseUri)
    }

    companion object {
        val TAG: String = AdblockModel::class.java.simpleName

        const val SERIALIZED_LIST_FILE = "adblock_ser.dat"
        const val AUTO_UPDATE_INTERVAL_MINUTES = 60 * 24 * 30 //30 days

        /** Checks the request with the client as [AdBlocker.isAd] does (also used by the debug benchmark) */
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
}
