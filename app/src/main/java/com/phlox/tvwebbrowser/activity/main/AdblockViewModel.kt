package com.phlox.tvwebbrowser.activity.main

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.webkit.WebResourceRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.brave.adblock.AdBlockClient
import com.brave.adblock.Utils
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*


class AdblockViewModel(val app: Application) : AndroidViewModel(app) {
    companion object {
        val TAG = AdblockViewModel::class.java.simpleName
        const val ADBLOCK_ENABLED_PREF_KEY = "adblock_enabled"
        const val ADBLOCK_LAST_UPDATE_LIST_KEY = "adblock_last_update"

        const val PREPACKED_LIST_FILE = "easylist.txt"
        const val SERIALIZED_LIST_FILE = "adblock_ser.dat"
    }

    private var prefs = app.getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
    var adBlockEnabled: Boolean = true
        set(value) {
            field = value
            prefs.edit().putBoolean(ADBLOCK_ENABLED_PREF_KEY, field).apply()
        }
    val client = AdBlockClient()
    val clientLoading = MutableLiveData(false)

    init {
        adBlockEnabled = prefs.getBoolean(ADBLOCK_ENABLED_PREF_KEY, true)

        run{
            if (prefs.contains(ADBLOCK_LAST_UPDATE_LIST_KEY)) {
                val lastUpdateListTime = prefs.getLong(ADBLOCK_LAST_UPDATE_LIST_KEY, 0)
                val lastUpdateAppTime: Long = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                if (lastUpdateAppTime > lastUpdateListTime) {
                    loadPrepackagedList(true)
                    return@run
                }
            }
            loadPrepackagedList(false)
        }
    }

    private fun loadPrepackagedList(forceReload: Boolean) = viewModelScope.launch {
        clientLoading.value = true
        withContext(Dispatchers.IO) ioContext@ {
            val serializedFile = File(app.filesDir, SERIALIZED_LIST_FILE)
            if ((!forceReload) && serializedFile.exists() && client.deserialize(serializedFile.absolutePath)) {
                return@ioContext
            }
            val easyList = app.assets.open(PREPACKED_LIST_FILE).bufferedReader().use { it.readText() }
            client.parse(easyList)
            client.serialize(serializedFile.absolutePath)
            prefs.edit().putLong(ADBLOCK_LAST_UPDATE_LIST_KEY, System.currentTimeMillis()).apply()
        }
        clientLoading.value = false
    }

    fun isAd(request: WebResourceRequest, baseUri: Uri): Boolean {
        val baseHost = baseUri.host
        return baseHost != null && client.matches(request.url.toString(), Utils.mapRequestToFilterOption(request), baseHost)
    }
}