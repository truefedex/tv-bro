package com.phlox.tvwebbrowser.activity.main

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.brave.adblock.AdBlockClient
import com.brave.adblock.Utils
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.*
import javax.net.ssl.HttpsURLConnection


class AdblockViewModel(val app: Application) : AndroidViewModel(app) {
    companion object {
        val TAG = AdblockViewModel::class.java.simpleName
        const val ADBLOCK_ENABLED_PREF_KEY = "adblock_enabled"
        const val ADBLOCK_LAST_UPDATE_LIST_KEY = "adblock_last_update"
        const val ADBLOCK_LIST_URL_KEY = "adblock_list_url"

        const val DEFAULT_LIST_URL = "https://easylist.to/easylist/easylist.txt"
        const val SERIALIZED_LIST_FILE = "adblock_ser.dat"
        const val AUTO_UPDATE_INTERVAL_MINUTES = 60 * 24 * 30 //30 days
    }

    private var prefs = app.getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
    var adBlockEnabled: Boolean = true
        set(value) {
            field = value
            prefs.edit().putBoolean(ADBLOCK_ENABLED_PREF_KEY, field).apply()
        }

    private var client: AdBlockClient? = null
    val clientLoading = MutableLiveData(false)
    var adBlockListURL = DEFAULT_LIST_URL
    var lastUpdateListTime: Long = 0

    init {
        adBlockEnabled = prefs.getBoolean(ADBLOCK_ENABLED_PREF_KEY, true)

        if (prefs.contains(ADBLOCK_LIST_URL_KEY)) {
            adBlockListURL = prefs.getString(ADBLOCK_LIST_URL_KEY, DEFAULT_LIST_URL)!!
        } else {
            prefs.edit().putString(ADBLOCK_LIST_URL_KEY, DEFAULT_LIST_URL).apply()//for users that may want to change it to simplify...
        }

        lastUpdateListTime = prefs.getLong(ADBLOCK_LAST_UPDATE_LIST_KEY, 0)

        loadAdBlockList(false)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    fun loadAdBlockList(forceReload: Boolean) = viewModelScope.launch {
        if (clientLoading.value!!) return@launch
        val checkDate = Calendar.getInstance()
        checkDate.timeInMillis = lastUpdateListTime
        checkDate.add(Calendar.MINUTE, AUTO_UPDATE_INTERVAL_MINUTES)
        val now = Calendar.getInstance()
        val needUpdate = forceReload || checkDate.before(now)
        clientLoading.value = true
        val client = AdBlockClient()
        var success = false
        withContext(Dispatchers.IO) ioContext@ {
            val serializedFile = File(app.filesDir, SERIALIZED_LIST_FILE)
            if ((!needUpdate) && serializedFile.exists() && client.deserialize(serializedFile.absolutePath)) {
                success = true
                return@ioContext
            }
            val easyList = URL(adBlockListURL).openConnection().inputStream.bufferedReader().use { it.readText() }
            success = client.parse(easyList)
            client.serialize(serializedFile.absolutePath)
        }
        this@AdblockViewModel.client = client
        lastUpdateListTime = now.timeInMillis
        prefs.edit().putLong(ADBLOCK_LAST_UPDATE_LIST_KEY, now.timeInMillis).apply()
        if (!success) {
            Toast.makeText(TVBro.instance, "Error loading ad-blocker list", Toast.LENGTH_SHORT).show()
        }
        clientLoading.value = false
    }

    fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean {
        val client = client ?: return false
        val baseHost = baseUri.host
        return baseHost != null && client.matches(request.url.toString(), Utils.mapRequestToFilterOption(request), baseHost)
    }
}