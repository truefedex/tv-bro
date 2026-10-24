package com.phlox.tvwebbrowser.service.downloads

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.os.BundleCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.utils.DownloadFileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Runs the downloads, in its own process (`:downloads`), so that they go on while the browser
 * switches between its normal and incognito processes, and both of them can start downloads.
 * The browser processes bind to it and send [DownloadCommands] through a [Messenger]; the state of
 * the downloads is written to the database, which the screens observe.
 */
class DownloadService : Service() {
    companion object {
        val TAG: String = DownloadService::class.java.simpleName
        const val DOWNLOAD_NOTIFICATION_ID = 101101
    }

    private val repository get() = TVBro.container.data.downloads
    private val scope = MainScope()
    private val executor = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    /** The running downloads by id, changed on the main thread */
    private val tasks = LinkedHashMap<Long, DownloadTask>()
    /** Downloads cancelled while their start was waiting for the database */
    private val cancelledBeforeStart = HashSet<Long>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { onCommand(it); true })
    private lateinit var recovery: Job
    private lateinit var notificationManager: NotificationManager
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var lastNotificationTime = 0L

    private val taskListener = object : DownloadTask.Listener {
        override fun onProgress(task: DownloadTask) {
            handler.post { updateNotification() }
        }

        override fun onEnded(task: DownloadTask) {
            handler.post { onTaskEnded(task) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        //rows inserted by the browser from now on are new downloads, the older ones in progress
        //were left by a process that died
        val startTime = System.currentTimeMillis()
        recovery = scope.launch {
            //the partial files of those that can not be resumed are of no use
            val interrupted = repository.withStatus(Download.Status.IN_PROGRESS).filter { it.time < startTime && !it.resumable }
            withContext(Dispatchers.IO) {
                interrupted.forEach { DownloadFileActions.deleteFile(this@DownloadService, it) }
            }
            repository.markInterrupted(startTime, Download.Error.INTERRUPTED)
        }
    }

    override fun onDestroy() {
        for (task in tasks.values) task.stop(DownloadTask.Stop.PAUSE)
        scope.cancel()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "The downloads ran out of the time of a data sync service")
        for (task in tasks.values) task.stop(DownloadTask.Stop.TIMEOUT)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onCommand(message: Message) {
        val data = message.data
        val id = data.getLong(DownloadCommands.KEY_ID)
        when (message.what) {
            DownloadCommands.START -> {
                val stream = BundleCompat.getParcelable(data, DownloadCommands.KEY_STREAM, ParcelFileDescriptor::class.java)
                start(id, data.getString(DownloadCommands.KEY_COOKIES), data.getString(DownloadCommands.KEY_REFERER),
                    data.getBoolean(DownloadCommands.KEY_INSTALL), stream, proxyOf(data))
            }
            DownloadCommands.CANCEL -> cancel(id)
            DownloadCommands.PAUSE -> tasks[id]?.stop(DownloadTask.Stop.PAUSE)
            DownloadCommands.RESUME -> resume(id, data.getString(DownloadCommands.KEY_COOKIES),
                data.getString(DownloadCommands.KEY_REFERER), proxyOf(data))
            else -> Log.w(TAG, "Unknown command ${message.what}")
        }
    }

    /** The proxy of the browser when it sent the command; null connects directly */
    private fun proxyOf(data: Bundle): ProxySettings? =
        ProxySettings.fromBundle(data.getBundle(DownloadCommands.KEY_PROXY))

    private fun start(id: Long, cookies: String?, referer: String?, install: Boolean, stream: ParcelFileDescriptor?,
                      proxy: ProxySettings?) {
        //the service is started too, so that it keeps running when the browser unbinds
        startService(Intent(this, DownloadService::class.java))
        startForeground()
        scope.launch {
            recovery.join()
            val download = repository.get(id)
            if (download == null || download.status != Download.Status.IN_PROGRESS || tasks.containsKey(id)) {
                Log.w(TAG, "Download $id can not be started")
                stream?.close()
                stopIfIdle()
                return@launch
            }
            download.cookies = cookies
            download.refererHeader = referer
            if (install) download.operationAfterDownload = Download.OperationAfterDownload.INSTALL
            Log.d(TAG, "Start to download ${download.url}")
            val task = if (stream != null) StreamDownloadTask(download, stream, taskListener)
                else HttpDownloadTask(download, taskListener, proxy = proxy)
            tasks[id] = task
            if (cancelledBeforeStart.remove(id)) task.stop(DownloadTask.Stop.CANCEL)
            executor.execute(task)
            updateNotification(force = true)
        }
    }

    /** Continues a paused download from the bytes its file has */
    private fun resume(id: Long, cookies: String?, referer: String?, proxy: ProxySettings?) {
        startService(Intent(this, DownloadService::class.java))
        startForeground()
        scope.launch {
            recovery.join()
            val download = repository.get(id)
            if (download == null || download.status != Download.Status.PAUSED || tasks.containsKey(id)) {
                Log.w(TAG, "Download $id can not be resumed")
                stopIfIdle()
                return@launch
            }
            download.status = Download.Status.IN_PROGRESS
            download.error = null
            repository.setStatus(id, Download.Status.IN_PROGRESS)
            download.cookies = cookies
            download.refererHeader = referer
            Log.d(TAG, "Resume to download ${download.url} from ${download.bytesReceived}")
            val task = HttpDownloadTask(download, taskListener, resume = true, proxy = proxy)
            tasks[id] = task
            if (cancelledBeforeStart.remove(id)) task.stop(DownloadTask.Stop.CANCEL)
            executor.execute(task)
            updateNotification(force = true)
        }
    }

    private fun cancel(id: Long) {
        val task = tasks[id]
        if (task != null) {
            task.stop(DownloadTask.Stop.CANCEL)
            return
        }
        //a download that is not running here (starting, paused, or left by a process that died)
        cancelledBeforeStart.add(id)
        scope.launch {
            recovery.join()
            val download = repository.get(id) ?: return@launch
            if (tasks.containsKey(id)) return@launch
            if (download.status != Download.Status.IN_PROGRESS && download.status != Download.Status.PAUSED) return@launch
            DownloadFileActions.deleteFile(this@DownloadService, download)
            repository.setStatus(id, Download.Status.CANCELLED)
        }
    }

    private fun onTaskEnded(task: DownloadTask) {
        tasks.remove(task.download.id)
        val download = task.download
        if (download.status == Download.Status.DONE &&
            download.operationAfterDownload == Download.OperationAfterDownload.INSTALL && canInstallPackages()) {
            DownloadFileActions.install(this, download)
        }
        updateNotification(force = true)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (tasks.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun canInstallPackages(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        packageManager.canRequestPackageInstalls()
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    }

    private fun startForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, DOWNLOAD_NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification(force: Boolean = false) {
        if (tasks.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationTime < DownloadTask.PROGRESS_INTERVAL) return
        lastNotificationTime = now
        notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        var downloaded = 0L
        var total = 0L
        var hasUnknownSizedFiles = false
        for (task in tasks.values) {
            downloaded += task.download.bytesReceived
            if (task.download.size > 0) {
                total += task.download.size
            } else {
                hasUnknownSizedFiles = true
            }
        }
        val title = tasks.values.joinToString(", ") { it.download.filename }
        val description = if (hasUnknownSizedFiles || total == 0L) {
            Formatter.formatShortFileSize(this, downloaded)
        } else {
            getString(R.string.downloaded_of, Formatter.formatShortFileSize(this, downloaded),
                Formatter.formatShortFileSize(this, total))
        }
        val builder = notificationBuilder ?: NotificationCompat.Builder(this, TVBro.CHANNEL_ID_DOWNLOADS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.KEY_SHOW_DOWNLOADS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .also { notificationBuilder = it }
        builder.setContentTitle(title).setContentText(description)
        if (hasUnknownSizedFiles || total == 0L) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (downloaded * 100 / total).toInt(), false)
        }
        return builder.build()
    }
}
