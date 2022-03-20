package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.model.*
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.FileUtils
import com.phlox.tvwebbrowser.utils.LogUtils
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import com.phlox.tvwebbrowser.utils.observable.ParameterizedEventSource
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class MainActivityViewModel: ActiveModel() {
    companion object {
        var TAG: String = MainActivityViewModel::class.java.simpleName
    }

    var loaded = false
    var lastHistoryItem: HistoryItem? = null
    val frequentlyUsedUrls = ObservableList<HistoryItem>()
    private var downloadIntent: DownloadIntent? = null
    val logCatOutput = ParameterizedEventSource<String>()

    init {
        if (Utils.isFireTV(TVBro.instance)) {
            launchLogcatOutputCoroutine()
        }
    }

    fun loadState() = modelScope.launch(Dispatchers.Main) {
        if (loaded) return@launch
        initHistory()
        loaded = true
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
            if (result.isNotEmpty()) {
                lastHistoryItem = result[0]
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.recordException(e)
        }

        try {
            frequentlyUsedUrls.addAll(AppDatabase.db.historyDao().frequentlyUsedUrls())
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.recordException(e)
        }
    }

    fun logVisitedHistory(title: String?, url: String, faviconHash: String?) {
        if ((url == lastHistoryItem?.url) || url == Config.DEFAULT_HOME_URL) {
            return
        }

        val item = HistoryItem()
        item.url = url
        item.title = title ?: ""
        item.time = Date().time
        item.favicon = faviconHash
        lastHistoryItem = item
        modelScope.launch(Dispatchers.Main) {
            AppDatabase.db.historyDao().insert(item)
        }
    }

    fun onDownloadRequested(activity: MainActivity, url: String, referer: String, originalDownloadFileName: String, userAgent: String, mimeType: String? = null,
                            operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
                            base64BlobData: String? = null) {
        downloadIntent = DownloadIntent(url, referer, originalDownloadFileName, userAgent, mimeType, operationAfterDownload, null, base64BlobData)
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

        DownloadService.startDownloading(TVBro.instance, download)

        activity.onDownloadStarted(fileName)
    }

    private fun launchLogcatOutputCoroutine() {
        modelScope.launch(Dispatchers.IO) {
            Runtime.getRuntime().exec("logcat -c")
            Runtime.getRuntime().exec("logcat")
              .inputStream
              .bufferedReader()
              .useLines { lines ->
                  lines.forEach {line ->
                      withContext(Dispatchers.Main) {
                          logCatOutput.emit(line)
                      }
                  }
              }
        }
    }

    fun clearIncognitoData() = modelScope.launch(Dispatchers.IO) {
        val webViewData = File(TVBro.instance.filesDir.parentFile!!.absolutePath + "/app_webview_incognito")
        FileUtils.deleteDirectory( webViewData )
        val webViewCache = File(TVBro.instance.cacheDir.absolutePath + "/webview_incognito")
        FileUtils.deleteDirectory( webViewCache )
    }
}