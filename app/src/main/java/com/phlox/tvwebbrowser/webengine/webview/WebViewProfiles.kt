package com.phlox.tvwebbrowser.webengine.webview

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.profile.KidsProfileData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The pages of the kids profile and of the incognito mode keep their data (cookies, storage, cache) apart, in
 * profiles of WebView of their own (`MULTI_PROFILE`, WebView 119+), in the one process of the app; the main
 * profile is the default profile of WebView. Without `MULTI_PROFILE` there is neither the kids profile nor the
 * incognito mode. A view gets its profile before anything else ([WebViewEx]); the global `CookieManager` and
 * `WebStorage` are those of the default profile, so the code of a view takes the ones of its profile here.
 *
 * WebView refuses to delete a profile that was used in the process, until the process ends: the data of such a
 * profile are wiped where it is ([wipe]: cookies, storage, permissions and cache, all of that profile only), and
 * a profile that has nothing to keep is deleted at the next start of the process, before it is used ([prepare]).
 * Nothing of WebView is asked before the app starts (only [deleteDataDirectoriesOfProcesses], files only).
 */
internal object WebViewProfiles {
    private const val TAG = "WebViewProfiles"
    const val KIDS = "kids"
    const val INCOGNITO = "incognito"

    val isSupported: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    /** The profile of a new view of a tab of the profile shown ([AppContext.kidsProfile]); null for the default one */
    fun of(incognito: Boolean): String? = when {
        !isSupported -> null
        AppContext.kidsProfile -> KIDS
        incognito -> INCOGNITO
        else -> null
    }

    /** The profile of the tabs shown now */
    fun ofShown(): String? = of(AppContext.provideConfig().incognitoMode)

    fun cookieManager(profile: String?): CookieManager =
        if (profile == null) CookieManager.getInstance() else ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager

    fun webStorage(profile: String?): WebStorage =
        if (profile == null) WebStorage.getInstance() else ProfileStore.getInstance().getOrCreateProfile(profile).webStorage

    /** A view of the [profile] that loads nothing, to clear the cache of the profile; destroyed by the caller */
    fun bareView(context: Context, profile: String?): WebView =
        WebView(context).also { if (profile != null) WebViewCompat.setProfile(it, profile) }

    private var prepared = false

    /**
     * Once per process, before any view: deletes the profiles that have nothing to keep (incognito out of
     * the incognito mode, kids without the data of the kids profile), and what the versions that ran them in
     * processes of their own left
     */
    @MainThread
    suspend fun prepare(context: Context) {
        if (prepared) return
        prepared = true
        if (!isSupported) return
        val store = ProfileStore.getInstance()
        val incognito = AppContext.provideConfig().incognitoMode
        val kidsData = withContext(Dispatchers.IO) { KidsProfileData.exists(context) }
        for (name in store.allProfileNames) {
            if ((name == INCOGNITO && !incognito) || (name == KIDS && !kidsData)) {
                try {
                    store.deleteProfile(name)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Can not delete the profile $name", e)
                }
            }
        }
    }

    /** Deletes the data of the pages of the [profile]: the profile itself if it was not used in this process */
    @MainThread
    suspend fun wipe(context: Context, profile: String) {
        if (!isSupported) return
        val store = ProfileStore.getInstance()
        try {
            store.deleteProfile(profile)
            return
        } catch (e: IllegalStateException) {
            //used in this process: wiped where it is, deleted at the next start (see prepare)
        }
        val data = store.getProfile(profile) ?: return
        suspendCoroutine { cont -> data.cookieManager.removeAllCookies { cont.resume(Unit) } }
        data.cookieManager.flush()
        data.webStorage.deleteAllData()
        data.geolocationPermissions.clearAll()
        val view = bareView(context, profile)
        view.clearCache(true)
        view.destroy()
    }

    /**
     * Versions before had the incognito mode and the kids profile in processes of their own, with data
     * directories of their own (Android 9+), and before Android 9 moved the main data directories aside while
     * the incognito mode was on: those are given back. When the app starts, before anything can load WebView
     * (files only: nothing of WebView is asked).
     */
    fun deleteDataDirectoriesOfProcesses(context: Context) {
        val dataRoot = context.filesDir.parentFile ?: return
        val cacheRoot = context.cacheDir
        for (suffix in listOf("incognito", "kids")) {
            File(dataRoot, "app_webview_$suffix").deleteRecursively()
            //the cache directory is named in another case on some versions of WebView
            for (name in listOf("webview_$suffix", "WebView_$suffix")) File(cacheRoot, name).deleteRecursively()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            for ((root, name) in listOf(dataRoot to "app_webview", cacheRoot to "WebView")) {
                val aside = File(root, name + "_backup")
                if (!aside.exists()) continue
                val current = File(root, name)
                current.deleteRecursively()
                if (!aside.renameTo(current)) Log.e(TAG, "Can not give back $aside")
            }
        }
    }
}
