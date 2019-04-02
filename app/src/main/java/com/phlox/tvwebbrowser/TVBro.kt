package com.phlox.tvwebbrowser

import android.app.Application
import com.phlox.tvwebbrowser.singleton.initASQL
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

        initASQL()
    }
}
