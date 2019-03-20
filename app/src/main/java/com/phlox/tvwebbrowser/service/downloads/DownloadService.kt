package com.phlox.tvwebbrowser.service.downloads

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.model.Download

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
        return Service.START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    fun startDownloading(url: String, fullDestFilePath: String, fileName: String, userAgent: String) {
        val download = Download()
        download.url = url
        download.filename = fileName
        download.filepath = fullDestFilePath
        download.time = Date().time
        try {
            asql!!.save(download)
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }

        val downloadTask = DownloadTask(download, userAgent, downloadTasksListener)
        activeDownloads.add(downloadTask)
        executor.execute(downloadTask)
        startService(Intent(this, DownloadService::class.java))
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
            if (activeDownloads.isEmpty()) {
                for (i in listeners.indices) {
                    listeners[i].onAllDownloadsComplete()
                }
                stopSelf()
            }
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
}
