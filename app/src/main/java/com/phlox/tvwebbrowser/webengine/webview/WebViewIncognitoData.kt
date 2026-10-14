package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.utils.deleteDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Keeps WebView data of the incognito mode apart from the normal one:
 * on API 28+ WebView of the incognito process uses another data directory,
 * on older APIs the normal data directory is moved aside while the incognito mode is on.
 */
internal object WebViewIncognitoData {
    private const val TAG = "WebViewIncognitoData"
    private const val WEB_VIEW_DATA_FOLDER = "app_webview"
    private const val WEB_VIEW_CACHE_FOLDER = "WebView"
    private const val WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX = "_backup"
    private const val INCOGNITO_DATA_DIRECTORY_SUFFIX = "incognito"

    private val dataRoot get() = AppContext.get().filesDir.parentFile!!.absolutePath
    private val cacheRoot get() = AppContext.get().cacheDir.absolutePath

    /**
     * Should be called in the incognito process before any WebView is created.
     */
    fun prepareIncognitoProcess() {
        Log.d(TAG, "prepareIncognitoProcess")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val incognitoWebViewData = File("$dataRoot/${WEB_VIEW_DATA_FOLDER}_$INCOGNITO_DATA_DIRECTORY_SUFFIX")
            if (incognitoWebViewData.exists()) {
                Log.i(TAG, "Looks like we already in incognito mode")
                return
            }
            WebView.setDataDirectorySuffix(INCOGNITO_DATA_DIRECTORY_SUFFIX)
        } else {
            val webViewData = File("$dataRoot/$WEB_VIEW_DATA_FOLDER")
            val backupedWebViewData = File("$dataRoot/$WEB_VIEW_DATA_FOLDER$WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX")
            if (backupedWebViewData.exists()) {
                Log.i(TAG, "Looks like we already in incognito mode")
                return
            }
            webViewData.renameTo(backupedWebViewData)
            val webViewCache = File("$cacheRoot/$WEB_VIEW_CACHE_FOLDER")
            val backupedWebViewCache = File("$cacheRoot/$WEB_VIEW_CACHE_FOLDER$WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX")
            webViewCache.renameTo(backupedWebViewCache)
        }
    }

    /**
     * Clears storage, cookies and cache of the running WebView.
     */
    suspend fun clearBrowsingData(context: Context) {
        withContext(Dispatchers.IO) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        WebView(context).clearCache(true)
    }

    /**
     * Deletes incognito data directories (and restores the normal ones on older APIs).
     */
    suspend fun deleteData() = withContext(Dispatchers.IO) {
        Log.d(TAG, "deleteData")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            deleteDirectory(File("$dataRoot/${WEB_VIEW_DATA_FOLDER}_$INCOGNITO_DATA_DIRECTORY_SUFFIX"))
            var webViewCache = File("$cacheRoot/${WEB_VIEW_CACHE_FOLDER}_$INCOGNITO_DATA_DIRECTORY_SUFFIX")
            if (!webViewCache.exists()) {
                webViewCache = File("$cacheRoot/${WEB_VIEW_CACHE_FOLDER.lowercase(Locale.getDefault())}_$INCOGNITO_DATA_DIRECTORY_SUFFIX")
            }
            deleteDirectory(webViewCache)
        } else {
            val webViewData = File("$dataRoot/$WEB_VIEW_DATA_FOLDER")
            deleteDirectory(webViewData)
            val webViewCache = File("$cacheRoot/$WEB_VIEW_CACHE_FOLDER")
            deleteDirectory(webViewCache)

            File("$dataRoot/$WEB_VIEW_DATA_FOLDER$WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX").renameTo(webViewData)
            File("$cacheRoot/$WEB_VIEW_CACHE_FOLDER$WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX").renameTo(webViewCache)
        }
    }
}
