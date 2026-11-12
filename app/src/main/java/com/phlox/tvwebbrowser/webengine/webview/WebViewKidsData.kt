package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the WebView data of the kids profile (cookies, storage, cache) apart from the main one: WebView of
 * the process of the kids profile uses another data directory, which stays between its starts. Before
 * Android 9 a process can not choose its data directory, so there is no kids profile there.
 */
internal object WebViewKidsData {
    private const val TAG = "WebViewKidsData"
    /** Names the directories (`app_webview_kids`): never change it */
    private const val DATA_DIRECTORY_SUFFIX = "kids"

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Deletes the data directories of the kids profile; its process must not be running */
    suspend fun deleteData(context: Context) = withContext(Dispatchers.IO) {
        val dataRoot = context.filesDir.parentFile ?: return@withContext
        File(dataRoot, "app_webview_$DATA_DIRECTORY_SUFFIX").deleteRecursively()
        //the cache directory is named in another case on some versions of WebView
        for (name in listOf("webview_$DATA_DIRECTORY_SUFFIX", "WebView_$DATA_DIRECTORY_SUFFIX")) {
            File(context.cacheDir, name).deleteRecursively()
        }
    }

    /** The directory is set for this process: WebView takes it once, and refuses it when it is loaded */
    private var prepared = false

    /**
     * Should be called in the process of the kids profile before any WebView is created; a new browser
     * activity of the same process calls it again
     */
    fun prepareKidsProcess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || prepared) return
        prepared = true
        try {
            WebView.setDataDirectorySuffix(DATA_DIRECTORY_SUFFIX)
        } catch (e: IllegalStateException) {
            //WebView is loaded already: its data would be those of the main profile
            Log.e(TAG, "The data directory of the kids profile can not be set", e)
            throw e
        }
    }
}
