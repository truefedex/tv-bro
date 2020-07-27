package com.phlox.tvwebbrowser.service.downloads

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import android.text.format.Formatter
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.DownloadIntent
import com.phlox.tvwebbrowser.singleton.AppDatabase
import java.io.File

import java.util.ArrayList
import java.util.Date
import java.util.concurrent.Executors

/**
 * Created by PDT on 23.01.2017.
 */

class DownloadService : Service() {
    private val activeDownloads = ArrayList<DownloadTask>()
    private val executor = Executors.newCachedThreadPool()
    private val listeners = ArrayList<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val binder = Binder()
    private var notificationBuilder: NotificationCompat.Builder? = null
    private lateinit var notificationManager: NotificationManager

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
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY
        val download = Download()
        val downloadIntent = intent.getParcelableExtra(KEY_DOWNLOAD) as DownloadIntent
        download.fillWith(downloadIntent)
        download.time = Date().time
        Log.d(TAG, "Start to downloading url: ${download.url}")
        val downloadTask = DownloadTask(download, downloadIntent.userAgent, downloadTasksListener)
        activeDownloads.add(downloadTask)
        executor.execute(downloadTask)
        startForeground(DOWNLOAD_NOTIFICATION_ID, updateNotification())
        return START_STICKY
    }

    private fun updateNotification(): Notification {
        var title = ""
        var downloaded = 0L
        var total = 0L
        var hasUnknownSizedFiles = false
        for (download in activeDownloads) {
            title += download.downloadInfo.filename + ","
            downloaded += download.downloadInfo.bytesReceived
            if (download.downloadInfo.size > 0) {
                total += download.downloadInfo.size
            } else {
                hasUnknownSizedFiles = true
            }
        }
        title.trim(',')
        val description = if (hasUnknownSizedFiles) {
            Formatter.formatShortFileSize(this, downloaded)
        } else {
            Formatter.formatShortFileSize(this, downloaded) + " of " +
                    Formatter.formatShortFileSize(this, total)
        }
        if (notificationBuilder == null) {
            notificationBuilder = NotificationCompat.Builder(this, TVBro.CHANNEL_ID_DOWNLOADS)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_launcher)
        }
        notificationBuilder!!.setContentTitle(title)
                .setContentText(description)
                .setSmallIcon(R.drawable.ic_launcher)
        if (hasUnknownSizedFiles || total == 0L) {
            notificationBuilder!!.setProgress(0, 0, true)
        } else {
            notificationBuilder!!.setProgress(100, (downloaded * 100 / total).toInt(), false)
        }
        return notificationBuilder!!.build()
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
        activeDownloads.remove(task)
        when (task.downloadInfo.operationAfterDownload) {
            Download.OperationAfterDownload.INSTALL -> {
                val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    packageManager.canRequestPackageInstalls()
                } else {
                    Settings.Secure.getInt(this.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1
                }
                if (canInstallFromOtherSources) {
                    launchInstallAPKActivity(this, task.downloadInfo)
                }
            }
            Download.OperationAfterDownload.NOP -> {}
        }
        if (activeDownloads.isEmpty()) {
            for (i in listeners.indices) {
                listeners[i].onAllDownloadsComplete()
            }
            stopForeground(true)
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
        AppDatabase.db.downloadDao().update(task.downloadInfo)
        handler.post {
            for (i in listeners.indices) {
                listeners[i].onDownloadUpdated(task.downloadInfo)
            }
            onTaskEnded(task)
        }
    }

    private fun notifyListenersAboutError(task: DownloadTask, responseCode: Int, responseMessage: String) {
        AppDatabase.db.downloadDao().update(task.downloadInfo)
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
            notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, updateNotification())
        }
    }

    inner class Binder : android.os.Binder() {
        val service: DownloadService
            get() = this@DownloadService
    }

    companion object {
        val TAG: String = DownloadService::class.java.simpleName
        const val DOWNLOAD_NOTIFICATION_ID = 101101
        private const val KEY_DOWNLOAD = "download"

        fun startDownloading(context: Context, downloadIntent: DownloadIntent) {
            val intent = Intent(context, DownloadService::class.java)
            intent.putExtra(KEY_DOWNLOAD, downloadIntent)
            context.startService(intent)
        }
    }
}
