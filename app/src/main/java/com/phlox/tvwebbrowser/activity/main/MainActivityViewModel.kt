package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.app.AlertDialog
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.Toast
import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.model.AndroidJSInterface
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.utils.StringUtils
import com.phlox.tvwebbrowser.utils.UpdateChecker
import com.phlox.tvwebbrowser.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

class MainActivityViewModel: ViewModel() {
    companion object {
        const val STATE_JSON = "state.json"
        var TAG = MainActivityViewModel.javaClass.simpleName
    }

    var needToCheckUpdateAgain: Boolean = false
    val asql by lazy { ASQL.getDefault(TVBro.instance) }
    val currentTab = MutableLiveData<WebTabState>()
    val tabsStates = ArrayList<WebTabState>()
    var lastHistoryItem: HistoryItem? = null
    val jsInterface = AndroidJSInterface()
    private var urlToDownload: String? = null
    private var originalDownloadFileName: String? = null
    private var userAgentForDownload: String? = null
    private var operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP

    fun saveState() {
        //WebTabState.saveTabs(TVBro.instance, tabsStates)
        val tabsCopy = tabsStates.map { it.copy() }//clone list

        GlobalScope.launch(Dispatchers.IO) {
            val store = JSONObject()
            val tabsStore = JSONArray()
            for (tab in tabsCopy) {
                val tabJson = tab.toJson(TVBro.instance, true)
                tabsStore.put(tabJson)
            }
            try {
                store.put("tabs", tabsStore)
                val fos = TVBro.instance.openFileOutput(STATE_JSON, Context.MODE_PRIVATE)
                try {
                    fos.write(store.toString().toByteArray())
                } finally {
                    fos.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadState() = GlobalScope.launch(Dispatchers.Main) {
        launch (Dispatchers.IO) { initHistory() }
        val tabsStatesLoaded = async (Dispatchers.IO){
            val tabsStates = ArrayList<WebTabState>()
            try {
                val fis = TVBro.instance.openFileInput(STATE_JSON)
                val storeStr = StringUtils.streamToString(fis)
                val store = JSONObject(storeStr)
                val tabsStore = store.getJSONArray("tabs")
                for (i in 0 until tabsStore.length()) {
                    val tab = WebTabState(TVBro.instance, tabsStore.getJSONObject(i))
                    tabsStates.add(tab)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            tabsStates
        }.await()

        tabsStates.addAll(tabsStatesLoaded)
    }

    private fun initHistory() {
        val count = asql.count(HistoryItem::class.java)
        if (count > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            asql.db.delete("history", "time < ?", arrayOf(java.lang.Long.toString(c.time.time)))
        }
        try {
            val result = asql.queryAll<HistoryItem>(HistoryItem::class.java, "SELECT * FROM history ORDER BY time DESC LIMIT 1")
            if (!result.isEmpty()) {
                lastHistoryItem = result.get(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val frequentlyUsedUrls = asql.queryAll<HistoryItem>(HistoryItem::class.java,
                    "SELECT title, url, favicon, count(url) as cnt , max(time) as time FROM history GROUP BY title, url, favicon ORDER BY cnt DESC, time DESC LIMIT 6")
            jsInterface.setSuggestions(TVBro.instance, frequentlyUsedUrls)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logVisitedHistory(title: String?, url: String?, faviconHash: String?) {
        if (url != null && (lastHistoryItem != null && url == lastHistoryItem!!.url || url == WebViewEx.HOME_URL)) {
            return
        }

        val item = HistoryItem()
        item.url = url
        item.title = title ?: ""
        item.time = Date().time
        item.favicon = faviconHash
        lastHistoryItem = item
        asql.execInsert("INSERT INTO history (time, title, url, favicon) VALUES (:time, :title, :url, :favicon)", lastHistoryItem) { lastInsertRowId, exception ->
            exception?.printStackTrace()
        }
    }

    fun checkUpdateIfNeeded(activity: MainActivity) = GlobalScope.launch(Dispatchers.Main) {
        val updateChecker = UpdateChecker(24/*BuildConfig.VERSION_CODE*/)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                updateChecker.check("https://raw.githubusercontent.com/truefedex/tv-bro/master/latest_version.json",
                        arrayOf("release"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.join()
        if (updateChecker.versionCheckResult != null &&
                updateChecker.versionCheckResult!!.latestVersionCode > updateChecker.currentVersionCode) {
            updateChecker.showUpdateDialog(activity, "release", object : UpdateChecker.DialogCallback {
                override fun download() {
                    val update = updateChecker.versionCheckResult ?: return

                    val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activity.packageManager.canRequestPackageInstalls()
                    } else
                        Settings.Secure.getInt(activity.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1

                    if(canInstallFromOtherSources) {
                        val filename = "update${update.latestVersionName}.apk"
                        onDownloadRequested(activity, update.url, filename, "tvbro-update-checker",
                                Download.OperationAfterDownload.INSTALL)
                    } else {
                        AlertDialog.Builder(activity)
                                .setTitle(R.string.app_name)
                                .setMessage(R.string.turn_on_unknown_sources)
                                .setPositiveButton(android.R.string.ok) { dialog, which -> run {
                                    val intent = Intent()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        intent.action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                                        intent.data = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                                    } else {
                                        intent.action = Settings.ACTION_SECURITY_SETTINGS
                                    }
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    try {
                                        activity.startActivityForResult(intent, MainActivity.REQUEST_CODE_UNKNOWN_APP_SOURCES)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                                    }
                                    needToCheckUpdateAgain = true
                                }}
                                .setNegativeButton(android.R.string.cancel) { dialog, which ->

                                }
                                .show()
                    }
                }

                override fun later() {

                }

                override fun settings() {

                }
            })
        }
    }

    fun onDownloadRequested(activity: MainActivity, url: String, originalDownloadFileName: String?, userAgent: String?,
                            operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP) {
        this.urlToDownload = url
        this.originalDownloadFileName = originalDownloadFileName
        this.userAgentForDownload = userAgent
        this.operationAfterDownload = operationAfterDownload
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    MainActivity.MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(activity)
        }
    }

    fun startDownload(activity: MainActivity) {
        val url = this.urlToDownload
        val originalFileName = this.originalDownloadFileName
        val userAgent = this.userAgentForDownload
        val operationAfterDownload = this.operationAfterDownload
        this.urlToDownload = null
        this.originalDownloadFileName = null
        this.userAgentForDownload = null
        this.operationAfterDownload = Download.OperationAfterDownload.NOP
        if (url == null || originalFileName == null) {
            Log.w(TAG, "Can not download without url or originalFileName")
            return
        }
        val extPos = originalFileName.lastIndexOf(".")
        val hasExt = extPos != -1
        var ext: String? = null
        var prefix: String? = null
        if (hasExt) {
            ext = originalFileName.substring(extPos + 1)
            prefix = originalFileName.substring(0, extPos)
        }
        var fileName = originalFileName
        var i = 0
        while (File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName).exists()) {
            i++
            if (hasExt) {
                fileName = prefix + "_(" + i + ")." + ext
            } else {
                fileName = originalFileName + "_(" + i + ")"
            }
        }

        if (Environment.MEDIA_MOUNTED != Environment.getExternalStorageState()) {
            Toast.makeText(activity, R.string.storage_not_mounted, Toast.LENGTH_SHORT).show()
            return
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            Toast.makeText(activity, R.string.can_not_create_downloads, Toast.LENGTH_SHORT).show()
            return
        }
        val fullDestFilePath = downloadsDir.toString() + File.separator + fileName
        DownloadService.startDownloading(activity, url, fullDestFilePath, fileName!!, userAgent!!, operationAfterDownload)

        activity.onDownloadStarted(fileName)
    }
}