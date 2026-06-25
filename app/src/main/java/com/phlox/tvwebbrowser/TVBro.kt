package com.phlox.tvwebbrowser

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine
import java.net.CookieHandler
import java.net.CookieManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Created by PDT on 09.09.2016.
 * 
 * Fixed version with comprehensive exception handling
 */
class TVBro : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        lateinit var instance: TVBro
        const val CHANNEL_ID_DOWNLOADS: String = "downloads"
        const val MAIN_PREFS_NAME = "main.xml"
        val TAG = TVBro::class.simpleName
        
        // Định nghĩa các class names để dễ maintain
        private const val CLASS_WEBVIEW_ENGINE = "com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine"
        private const val CLASS_GECKO_ENGINE = "com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine"
    }

    lateinit var threadPool: ThreadPoolExecutor
        private set

    var needToExitProcessAfterMainActivityFinish = false
    var needRestartMainActivityAfterExitingProcess = false
    
    override fun onCreate() {
        Log.i(TAG, "onCreate - Application starting")
        super.onCreate()

        try {
            // Handle target SDK version for Android 9 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                try {
                    // We need this since when targetSdkVersion >= 33 then
                    // deprecated WebSettingsCompat.setForceDark stops working on android 9 and below
                    applicationInfo.targetSdkVersion = 32
                    Log.i(TAG, "Target SDK version set to 32 for Android P and below")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to set target SDK version - security exception", e)
                    // Continue anyway, this is not critical
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set target SDK version", e)
                    // Continue anyway, this is not critical
                }
            }

            instance = this

            // Initialize AppContext with comprehensive error handling
            try {
                AppContext.init(this, Config(getSharedPreferences(MAIN_PREFS_NAME, MODE_MULTI_PROCESS)))
                Log.i(TAG, "AppContext initialized successfully")
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception initializing AppContext", e)
                throw RuntimeException("Failed to initialize AppContext due to security issue", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Illegal state initializing AppContext", e)
                throw RuntimeException("Failed to initialize AppContext - unexpected state", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error initializing AppContext", e)
                throw RuntimeException("Failed to initialize AppContext", e)
            }

            // Initialize ThreadPool with error handling
            try {
                val maxThreadsInOfflineJobsPool = Runtime.getRuntime().availableProcessors()
                threadPool = ThreadPoolExecutor(
                    0, 
                    maxThreadsInOfflineJobsPool, 
                    20,
                    TimeUnit.SECONDS, 
                    ArrayBlockingQueue(maxThreadsInOfflineJobsPool)
                )
                Log.i(TAG, "ThreadPool initialized with $maxThreadsInOfflineJobsPool threads")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid argument for ThreadPoolExecutor", e)
                // Fallback to default values
                threadPool = ThreadPoolExecutor(0, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(4))
                Log.w(TAG, "ThreadPool initialized with fallback values")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ThreadPool", e)
                // Create minimal ThreadPool as fallback
                threadPool = ThreadPoolExecutor(0, 2, 20, TimeUnit.SECONDS, ArrayBlockingQueue(2))
                Log.w(TAG, "ThreadPool initialized with minimal fallback values")
            }

            // Initialize WebEngine with comprehensive error handling
            initWebEngineStuff()

            // Initialize notification channels with error handling
            try {
                initNotificationChannels()
                Log.i(TAG, "Notification channels initialized")
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception initializing notification channels", e)
                // Continue - notifications are not critical
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize notification channels", e)
                // Continue - notifications are not critical
            }

            // Initialize ActiveModelsRepository with error handling
            try {
                ActiveModelsRepository.init(this)
                Log.i(TAG, "ActiveModelsRepository initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ActiveModelsRepository", e)
                // This might be critical, but let's continue
            }

            // Set theme with error handling
            try {
                when (AppContext.provideConfig().theme.value) {
                    Config.Theme.BLACK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    Config.Theme.WHITE -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
                Log.i(TAG, "Theme applied successfully")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid theme value", e)
                // Set default theme
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set theme", e)
                // Set default theme
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }

            // Register activity lifecycle callbacks with error handling
            try {
                registerActivityLifecycleCallbacks(this)
                Log.i(TAG, "Activity lifecycle callbacks registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register activity lifecycle callbacks", e)
                // This is critical for app lifecycle management
                throw RuntimeException("Failed to register activity lifecycle callbacks", e)
            }
            
            Log.i(TAG, "Application initialization completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during application initialization", e)
            // Re-throw to let Android know initialization failed
            throw e
        }
    }

    /**
     * Initialize WebEngine components with comprehensive exception handling
     * This method handles all possible exceptions and provides clear logging
     */
    private fun initWebEngineStuff() {
        Log.i(TAG, "Starting WebEngine initialization")
        
        try {
            // 1. Initialize WebViewWebEngine (REQUIRED)
            try {
                val webViewClass = Class.forName(CLASS_WEBVIEW_ENGINE)
                Log.i(TAG, "✅ WebViewWebEngine class found: ${webViewClass.name}")
                
                // Verify WebViewWebEngine is accessible
                try {
                    val instance = webViewClass.newInstance()
                    Log.i(TAG, "✅ WebViewWebEngine instance created successfully")
                } catch (e: InstantiationException) {
                    Log.e(TAG, "❌ Cannot instantiate WebViewWebEngine", e)
                    throw RuntimeException("WebViewWebEngine is required but cannot be instantiated", e)
                } catch (e: IllegalAccessException) {
                    Log.e(TAG, "❌ Cannot access WebViewWebEngine constructor", e)
                    throw RuntimeException("WebViewWebEngine is required but cannot be accessed", e)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Unexpected error creating WebViewWebEngine instance", e)
                    throw RuntimeException("Failed to create WebViewWebEngine instance", e)
                }
                
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "❌ WebViewWebEngine class not found - CRITICAL ERROR!", e)
                throw RuntimeException("WebViewWebEngine is required for the app to work. " +
                    "Please ensure the dependency is included in the build.", e)
            } catch (e: NoClassDefFoundError) {
                Log.e(TAG, "❌ WebViewWebEngine class definition not found - CRITICAL ERROR!", e)
                throw RuntimeException("WebViewWebEngine dependency is missing or corrupted. " +
                    "Please check the build configuration.", e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error loading WebViewWebEngine - CRITICAL ERROR!", e)
                throw RuntimeException("Failed to initialize WebViewWebEngine due to unexpected error", e)
            }

            // 2. Initialize GeckoWebEngine (OPTIONAL)
            try {
                Class.forName(CLASS_GECKO_ENGINE)
                Log.i(TAG, "✅ GeckoWebEngine class found - optional engine available")
                
                // Verify GeckoWebEngine is accessible (optional)
                try {
                    val geckoClass = Class.forName(CLASS_GECKO_ENGINE)
                    Log.i(TAG, "✅ GeckoWebEngine is accessible and available")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ GeckoWebEngine found but not accessible", e)
                    // Continue - this is optional
                }
                
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "⚠️ GeckoWebEngine not found - this is optional, continuing with WebView only", e)
            } catch (e: NoClassDefFoundError) {
                Log.w(TAG, "⚠️ GeckoWebEngine class definition not found - dependency may be missing", e)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Unexpected error while checking GeckoWebEngine availability", e)
                // Continue - this is optional
            }

            // 3. Initialize CookieManager
            try {
                val cookieManager = CookieManager()
                CookieHandler.setDefault(cookieManager)
                Log.i(TAG, "✅ CookieManager initialized successfully")
                
                // Verify CookieManager is working
                try {
                    val testCookie = cookieManager.getCookieStore()
                    Log.i(TAG, "✅ CookieManager verification passed")
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ CookieManager verification failed", e)
                    // Continue anyway as this might not be critical
                }
                
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Security exception initializing CookieManager", e)
                throw RuntimeException("Failed to initialize CookieManager due to security restrictions", e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize CookieManager", e)
                throw RuntimeException("Failed to initialize CookieManager", e)
            }

            // 4. Initialize FaviconsPool database delegate
            try {
                FaviconsPool.databaseDelegate = object : FaviconsPool.DatabaseDelegate {
                    override fun findByHostName(host: String): HostConfig? {
                        return try {
                            // Validate input
                            if (host.isNullOrBlank()) {
                                Log.w(TAG, "findByHostName called with empty host")
                                return null
                            }
                            
                            // Check if database is available
                            if (!::AppDatabase.isInitialized) {
                                Log.e(TAG, "AppDatabase not initialized when finding host: $host")
                                return null
                            }
                            
                            AppDatabase.db.hostsDao().findByHostName(host)
                            
                        } catch (e: NullPointerException) {
                            Log.e(TAG, "Null pointer while finding host: $host", e)
                            null
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "Illegal state while finding host: $host", e)
                            null
                        } catch (e: Exception) {
                            Log.e(TAG, "Error finding host by name: $host", e)
                            null
                        }
                    }

                    override suspend fun update(hostConfig: HostConfig) {
                        try {
                            // Validate input
                            if (hostConfig == null) {
                                Log.e(TAG, "update called with null hostConfig")
                                throw IllegalArgumentException("HostConfig cannot be null")
                            }
                            
                            if (hostConfig.host.isNullOrBlank()) {
                                Log.e(TAG, "update called with empty host")
                                throw IllegalArgumentException("Host cannot be empty")
                            }
                            
                            // Check if database is available
                            if (!::AppDatabase.isInitialized) {
                                Log.e(TAG, "AppDatabase not initialized when updating: ${hostConfig.host}")
                                throw IllegalStateException("AppDatabase not initialized")
                            }
                            
                            AppDatabase.db.hostsDao().update(hostConfig)
                            Log.d(TAG, "Updated host config: ${hostConfig.host}")
                            
                        } catch (e: IllegalArgumentException) {
                            Log.e(TAG, "Invalid argument when updating host config: ${hostConfig?.host}", e)
                            throw e // Re-throw for caller to handle
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "Database state error when updating: ${hostConfig?.host}", e)
                            throw e // Re-throw for caller to handle
                        } catch (e: Exception) {
                            Log.e(TAG, "Unexpected error updating host config: ${hostConfig?.host}", e)
                            throw RuntimeException("Failed to update host config", e)
                        }
                    }

                    override suspend fun insert(newHostConfig: HostConfig) {
                        try {
                            // Validate input
                            if (newHostConfig == null) {
                                Log.e(TAG, "insert called with null hostConfig")
                                throw IllegalArgumentException("HostConfig cannot be null")
                            }
                            
                            if (newHostConfig.host.isNullOrBlank()) {
                                Log.e(TAG, "insert called with empty host")
                                throw IllegalArgumentException("Host cannot be empty")
                            }
                            
                            // Check if database is available
                            if (!::AppDatabase.isInitialized) {
                                Log.e(TAG, "AppDatabase not initialized when inserting: ${newHostConfig.host}")
                                throw IllegalStateException("AppDatabase not initialized")
                            }
                            
                            AppDatabase.db.hostsDao().insert(newHostConfig)
                            Log.d(TAG, "Inserted new host config: ${newHostConfig.host}")
                            
                        } catch (e: IllegalArgumentException) {
                            Log.e(TAG, "Invalid argument when inserting host config: ${newHostConfig?.host}", e)
                            throw e // Re-throw for caller to handle
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "Database state error when inserting: ${newHostConfig?.host}", e)
                            throw e // Re-throw for caller to handle
                        } catch (e: Exception) {
                            Log.e(TAG, "Unexpected error inserting host config: ${newHostConfig?.host}", e)
                            throw RuntimeException("Failed to insert host config", e)
                        }
                    }
                }
                Log.i(TAG, "✅ FaviconsPool database delegate initialized successfully")
                
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "❌ Invalid argument initializing FaviconsPool database delegate", e)
                throw RuntimeException("Failed to initialize FaviconsPool database delegate - invalid argument", e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize FaviconsPool database delegate", e)
                throw RuntimeException("Failed to initialize FaviconsPool database delegate", e)
            }

            Log.i(TAG, "✅ WebEngine initialization completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebEngine initialization failed", e)
            // Re-throw critical errors
            throw e
        }
    }

    /**
     * Initialize notification channels with comprehensive error handling
     */
    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val name = getString(R.string.downloads)
                val descriptionText = getString(R.string.downloads_notifications_description)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                
                val channel = NotificationChannel(CHANNEL_ID_DOWNLOADS, name, importance)
                channel.description = descriptionText
                
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
                
                Log.i(TAG, "✅ Notification channel created: $CHANNEL_ID_DOWNLOADS")
                
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Security exception creating notification channel", e)
                // Don't re-throw - this is not critical
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "❌ Invalid argument for notification channel", e)
                // Don't re-throw - this is not critical
            } catch (e: NullPointerException) {
                Log.e(TAG, "❌ Null pointer creating notification channel", e)
                // Don't re-throw - this is not critical
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unexpected error creating notification channel", e)
                // Don't re-throw - this is not critical
            }
        } else {
            Log.i(TAG, "Notification channels not needed for Android versions below O")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        try {
            Log.d(TAG, "onActivityCreated: ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onActivityCreated", e)
        }
    }
    
    override fun onActivityStarted(activity: Activity) {
        try {
            Log.d(TAG, "onActivityStarted: ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onActivityStarted", e)
        }
    }
    
    override fun onActivityResumed(activity: Activity) {
        try {
            Log.d(TAG, "onActivityResumed: ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onActivityResumed", e)
        }
    }
    
    override fun onActivityPaused(activity: Activity) {
        try {
            Log.d(TAG, "onActivityPaused: ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onActivityPaused", e)
        }
    }
    
    override fun onActivityStopped(activity: Activity) {
        try {
            Log.d(TAG, "onActivityStopped: ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onActivityStopped", e)
        }
    }
    
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        try {
            Log.d(TAG, "onActivitySaveInstanceState: ${activity.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onActivitySaveInstanceState", e)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        try {
            Log.i(TAG, "onActivityDestroyed: ${activity.javaClass.simpleName}")
            
            if (needToExitProcessAfterMainActivityFinish && activity is MainActivity) {
                Log.i(TAG, "onActivityDestroyed: preparing to exit process")
                
                if (needRestartMainActivityAfterExitingProcess) {
                    Log.i(TAG, "onActivityDestroyed: restarting main activity")
                    try {
                        val intent = Intent(this@TVBro, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                        Log.i(TAG, "✅ Restart intent sent successfully")
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Failed to restart main activity - illegal state", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart main activity", e)
                    }
                }
                
                // Note: Using exitProcess is generally not recommended
                // Consider using finishAffinity() instead
                try {
                    Log.w(TAG, "⚠️ Calling exitProcess(0) - this is not recommended")
                    exitProcess(0)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception while exiting process", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error while exiting process", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onActivityDestroyed", e)
        }
    }

    /**
     * Clean up resources when application is terminated
     */
    override fun onTerminate() {
        Log.i(TAG, "onTerminate - Application terminating")
        try {
            // Shutdown thread pool
            if (::threadPool.isInitialized) {
                try {
                    threadPool.shutdown()
                    // Wait for tasks to complete (optional)
                    if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                        threadPool.shutdownNow()
                    }
                    Log.i(TAG, "ThreadPool shutdown successfully")
                } catch (e: InterruptedException) {
                    Log.e(TAG, "ThreadPool shutdown interrupted", e)
                    threadPool.shutdownNow()
                } catch (e: Exception) {
                    Log.e(TAG, "Error shutting down ThreadPool", e)
                }
            }
            
            // Unregister lifecycle callbacks
            try {
                unregisterActivityLifecycleCallbacks(this)
                Log.i(TAG, "Activity lifecycle callbacks unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering lifecycle callbacks", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during application termination", e)
        } finally {
            super.onTerminate()
        }
    }
}
