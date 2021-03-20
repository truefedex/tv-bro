package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.model.*
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.*
import kotlin.collections.ArrayList


class MainActivityViewModel: ViewModel() {
    companion object {
        const val STATE_JSON = "state.json"
        var TAG: String = MainActivityViewModel::class.java.simpleName
    }

    var loaded = false
    var incognitoMode = false
    val currentTab = MutableLiveData<WebTabState>()
    val tabsStates = ArrayList<WebTabState>()
    var lastHistoryItem: HistoryItem? = null
    val jsInterface = AndroidJSInterface(this)
    private var downloadIntent: DownloadIntent? = null

    fun loadState() = viewModelScope.launch(Dispatchers.Main) {
        if (loaded) return@launch
        initHistory()
        val tabsDao = AppDatabase.db.tabsDao()
        val stateFile = File(TVBro.instance.filesDir, STATE_JSON)
        if (stateFile.exists()) {
            val tabsStatesLoadedFromLegacyJson = async(Dispatchers.IO) {
                val tabsStates = ArrayList<WebTabState>()
                try {
                    val storeStr = FileInputStream(stateFile).bufferedReader().use { it.readText() }
                    val store = JSONObject(storeStr)
                    val tabsStore = store.getJSONArray("tabs")
                    for (i in 0 until tabsStore.length()) {
                        val tab = WebTabState(TVBro.instance, tabsStore.getJSONObject(i))
                        tabsStates.add(tab)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    LogUtils.recordException(e)
                }
                stateFile.delete()
                tabsStates
            }.await()
            //tabsDao.deleteAll(incognitoMode)
            tabsStatesLoadedFromLegacyJson.forEachIndexed { index, webTabState ->
                webTabState.position = index
                tabsDao.insert(webTabState)
            }
        }
        tabsStates.addAll(tabsDao.getAll(incognitoMode))
        loaded = true
    }

    fun saveTab(tab: WebTabState, saveAlsoSelectionAndPositions: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main) {
            val tabsDB = AppDatabase.db.tabsDao()

            if (saveAlsoSelectionAndPositions) {
                tabsDB.unselectAll(incognitoMode)
            }
            tab.saveWebViewStateToFile()
            if (tab.id != 0L ) {
                tabsDB.update(tab)
            } else {
                tab.id = tabsDB.insert(tab)
            }

            if (saveAlsoSelectionAndPositions) {
                tabsDB.updatePositions(tabsStates)
            }
        }
    }

    fun onCloseTab(tab: WebTabState) {
        tabsStates.remove(tab)
        viewModelScope.launch(Dispatchers.Main) {
            val tabsDB = AppDatabase.db.tabsDao()
            tabsDB.delete(tab)
            launch { tab.removeFiles() }
        }
    }

    fun onCloseAllTabs() {
        val tabsClone = ArrayList(tabsStates)
        tabsStates.clear()
        viewModelScope.launch(Dispatchers.Main) {
            val tabsDB = AppDatabase.db.tabsDao()
            tabsDB.deleteAll(incognitoMode)
            launch { tabsClone.forEach { it.removeFiles() } }
        }
    }

    private suspend fun initHistory() {
        val count = AppDatabase.db.historyDao().count()
        if (count > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            AppDatabase.db.historyDao().deleteWhereTimeLessThan(c.time.time)
        }
        try {
            val result = AppDatabase.db.historyDao().last()
            if (!result.isEmpty()) {
                lastHistoryItem = result.get(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.recordException(e)
        }

        try {
            val frequentlyUsedUrls = AppDatabase.db.historyDao().frequentlyUsedUrls()
            jsInterface.setSuggestions(TVBro.instance, frequentlyUsedUrls)
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.recordException(e)
        }
    }

    fun logVisitedHistory(title: String?, url: String?, faviconHash: String?) {
        if (url != null && (lastHistoryItem != null && url == lastHistoryItem!!.url || url == WebViewEx.HOME_URL)) {
            return
        }

        val item = HistoryItem()
        item.url = url ?: ""
        item.title = title ?: ""
        item.time = Date().time
        item.favicon = faviconHash
        lastHistoryItem = item
        viewModelScope.launch(Dispatchers.Main) {
            AppDatabase.db.historyDao().insert(item)
        }
    }

    fun onDownloadRequested(activity: MainActivity, url: String, referer: String, originalDownloadFileName: String, userAgent: String, mimeType: String? = null,
                            operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP) {
        downloadIntent = DownloadIntent(url, referer, originalDownloadFileName, userAgent, mimeType, operationAfterDownload)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    MainActivity.MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(activity)
        }
    }

    fun startDownload(activity: MainActivity) {
        val download = this.downloadIntent ?: return
        this.downloadIntent = null
        val extPos = download.fileName.lastIndexOf(".")
        val hasExt = extPos != -1
        var ext: String? = null
        var prefix: String? = null
        if (hasExt) {
            ext = download.fileName.substring(extPos + 1)
            prefix = download.fileName.substring(0, extPos)
        }
        var fileName = download.fileName
        var i = 0
        while (File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + File.separator + fileName).exists()) {
            i++
            if (hasExt) {
                fileName = prefix + "_(" + i + ")." + ext
            } else {
                fileName = download.fileName + "_(" + i + ")"
            }
        }
        download.fileName = fileName

        if (Environment.MEDIA_MOUNTED != Environment.getExternalStorageState()) {
            Toast.makeText(activity, R.string.storage_not_mounted, Toast.LENGTH_SHORT).show()
            return
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            Toast.makeText(activity, R.string.can_not_create_downloads, Toast.LENGTH_SHORT).show()
            return
        }
        download.fullDestFilePath = downloadsDir.toString() + File.separator + fileName

        DownloadService.startDownloading(activity, download)

        activity.onDownloadStarted(fileName)
    }

    fun logCatOutput() = liveData(viewModelScope.coroutineContext + Dispatchers.IO) {
        Runtime.getRuntime().exec("logcat -c")
        Runtime.getRuntime().exec("logcat")
                .inputStream
                .bufferedReader()
                .useLines { lines -> lines.forEach { line -> emit(line) }
                }
    }

    fun onDetachActivity() {
        for (tab in tabsStates) {
            tab.recycleWebView()
        }
    }
}