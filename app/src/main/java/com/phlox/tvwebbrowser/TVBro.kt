package com.phlox.tvwebbrowser

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import java.net.CookieHandler
import java.net.CookieManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Created by PDT on 09.09.2016.
 */
class TVBro : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        lateinit var instance: TVBro
        const val CHANNEL_ID_DOWNLOADS: String = "downloads"
        const val MAIN_PREFS_NAME = "main.xml"
        const val INCOGNITO_DATA_DIRECTORY_SUFFIX = "incognito"
        val TAG = TVBro::class.simpleName

        val config: Config get() = instance._config
    }

    lateinit var threadPool: ThreadPoolExecutor
        private set

    private lateinit var _config: Config

    override fun onCreate() {
        super.onCreate()

        instance = this

        _config = Config(getSharedPreferences(MAIN_PREFS_NAME, 0))

        val maxThreadsInOfflineJobsPool = Runtime.getRuntime().availableProcessors()
        threadPool = ThreadPoolExecutor(0, maxThreadsInOfflineJobsPool, 20,
                TimeUnit.SECONDS, ArrayBlockingQueue(maxThreadsInOfflineJobsPool))

        initWebView()

        initNotificationChannels()

        ActiveModelsRepository.init(this)

        when (_config.theme) {
            Config.Theme.BLACK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            Config.Theme.WHITE -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        registerActivityLifecycleCallbacks(this)
    }

    private fun initWebView() {
        Log.i(TAG, "initWebView")
        if (config.incognitoMode && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)) {
            WebView.setDataDirectorySuffix(INCOGNITO_DATA_DIRECTORY_SUFFIX)
        }
        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)
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

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        //we need this because in case of IncognitoModeMainActivity closed by exit button by user
        //then incognito mode becomes closed but process are still running and this lead to
        //strange problems at next time when we trying to start the incognito mode
        if (activity is IncognitoModeMainActivity) {
            Process.killProcess(Process.myPid())
        }
    }
}
