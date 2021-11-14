package com.phlox.tvwebbrowser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import java.net.CookieHandler
import java.net.CookieManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Created by PDT on 09.09.2016.
 */
class TVBro : Application() {
    companion object {
        lateinit var instance: TVBro
        const val CHANNEL_ID_DOWNLOADS: String = "downloads"
        const val MAIN_PREFS_NAME = "main.xml"
    }

    lateinit var threadPool: ThreadPoolExecutor
        private set

    override fun onCreate() {
        super.onCreate()

        instance = this

        val maxThreadsInOfflineJobsPool = Runtime.getRuntime().availableProcessors()
        threadPool = ThreadPoolExecutor(0, maxThreadsInOfflineJobsPool, 20,
                TimeUnit.SECONDS, ArrayBlockingQueue(maxThreadsInOfflineJobsPool))

        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)

        initNotificationChannels()

        ActiveModelsRepository.init(this)
    }

    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.downloads)
            val descriptionText = getString(R.string.downloads_notifications_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID_DOWNLOADS, name, importance)
            channel.description = descriptionText
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
