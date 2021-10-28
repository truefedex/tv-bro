package com.phlox.tvwebbrowser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.phlox.tvwebbrowser.utils.statemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.statemodel.ActiveModelUser
import com.phlox.tvwebbrowser.utils.statemodel.ActiveModelsRepository
import java.net.CookieHandler
import java.net.CookieManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Created by PDT on 09.09.2016.
 */
class TVBro : Application() {
    companion object {
        lateinit var instance: TVBro
        const val CHANNEL_ID_DOWNLOADS: String = "downloads"
        const val MAIN_PREFS_NAME = "main.xml"

        fun <T: ActiveModel>get(clazz: KClass<T>, user: ActiveModelUser): T {
            return instance.models.get(clazz, user)
        }
    }

    lateinit var threadPool: ThreadPoolExecutor
        private set

    val models = ActiveModelsRepository(this)

    override fun onCreate() {
        super.onCreate()

        instance = this

        val maxThreadsInOfflineJobsPool = Runtime.getRuntime().availableProcessors()
        threadPool = ThreadPoolExecutor(0, maxThreadsInOfflineJobsPool, 20,
                TimeUnit.SECONDS, ArrayBlockingQueue(maxThreadsInOfflineJobsPool))

        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)

        initNotificationChannels()
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
