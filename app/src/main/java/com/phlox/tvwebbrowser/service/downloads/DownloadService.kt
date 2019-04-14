package com.phlox.tvwebbrowser.service.downloads

import android.app.Activity
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.support.v4.content.FileProvider
import android.webkit.MimeTypeMap
import android.widget.Toast

import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.Download
import java.io.File

import java.util.ArrayList
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Created by PDT on 23.01.2017.
 */

class DownloadService : Service() {
    private val activeDownloads = ArrayList<DownloadTask>()
    private var asql: ASQL? = null
    private val executor = Executors.newCachedThreadPool()
    private val listeners = ArrayList<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val binder = Binder()

    internal var downloadTasksListener: DownloadTask.Callback = object : DownloadTask.Callback {
        internal val MIN_NOTIFY_TIMEOUT = 100
        private var lastNotifyTime = System.currentTimeMillis()
        override fun onProgress(task: DownloadTask) {
            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > MIN_NOTIFY_TIMEOUT) {
                lastNotifyTime = now
                notifyListeners(task)
            }
        }

        override fun onError(task: DownloadTask, responseCode: Int, responseMessage: String) {
            notifyListenersAboutError(task, responseCode, responseMessage)
        }

        override fun onDone(task: DownloadTask) {
            notifyListenersAboutDownloadDone(task)
        }
    }

    interface Listener {
        fun onDownloadUpdated(downloadInfo: Download)
        fun onDownloadError(downloadInfo: Download, responseCode: Int, responseMessage: String)
        fun onAllDownloadsComplete()
    }

    override fun onCreate() {
        super.onCreate()
        asql = ASQL.getDefault(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val download = intent.getSerializableExtra("download") as Download
        val userAgent = intent.getStringExtra("userAgent")
        try {
            asql!!.save(download)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }

        val downloadTask = DownloadTask(download, userAgent, downloadTasksListener)
        activeDownloads.add(downloadTask)
        executor.execute(downloadTask)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    fun cancelDownload(download: Download) {
        for (i in activeDownloads.indices) {
            val task = activeDownloads[i]
            if (task.downloadInfo.id == download.id) {
                task.isCancelled = true
                break
            }
        }
    }

    fun registerListener(listener: Listener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun onTaskEnded(task: DownloadTask) {
        asql!!.save(task.downloadInfo) { result, exception ->
            activeDownloads.remove(task)
            when (task.downloadInfo.operationAfterDownload) {
                Download.OperationAfterDownload.INSTALL -> {
                    val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        packageManager.canRequestPackageInstalls()
                    } else
                        Settings.Secure.getInt(this.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1
                    if (canInstallFromOtherSources) {
                        launchInstallAPKActivity(this, task.downloadInfo)
                    }
                }
            }
            if (activeDownloads.isEmpty()) {
                for (i in listeners.indices) {
                    listeners[i].onAllDownloadsComplete()
                }
                stopSelf()
            }
        }
    }

    fun launchInstallAPKActivity(context: Context, download: Download) {
        val file = File(download.filepath)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
        val apkURI = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider", file)

        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
        install.setDataAndType(apkURI, mimeType)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifyListenersAboutDownloadDone(task: DownloadTask) {
        handler.post {
            for (i in listeners.indices) {
                listeners[i].onDownloadUpdated(task.downloadInfo)
            }
            onTaskEnded(task)
        }
    }

    private fun notifyListenersAboutError(task: DownloadTask, responseCode: Int, responseMessage: String) {
        handler.post {
            for (i in listeners.indices) {
                listeners[i].onDownloadError(task.downloadInfo, responseCode, responseMessage)
            }
            onTaskEnded(task)
        }
    }

    private fun notifyListeners(task: DownloadTask) {
        handler.post {
            for (i in listeners.indices) {
                listeners[i].onDownloadUpdated(task.downloadInfo)
            }
        }
    }

    inner class Binder : android.os.Binder() {
        val service: DownloadService
            get() = this@DownloadService
    }

    companion object {
        fun startDownloading(context: Context, url: String, fullDestFilePath: String, fileName: String, userAgent: String,
                             operationAfterDownload: Download.OperationAfterDownload) {
            val download = Download()
            download.url = url
            download.filename = fileName
            download.filepath = fullDestFilePath
            download.time = Date().time
            download.operationAfterDownload = operationAfterDownload
            val intent = Intent(context, DownloadService::class.java)
            intent.putExtra("download", download)
            intent.putExtra("userAgent", userAgent)
            context.startService(intent)
        }
    }
}
