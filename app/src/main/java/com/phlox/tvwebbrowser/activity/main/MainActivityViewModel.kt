package com.phlox.tvwebbrowser.activity.main

import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.LogUtils
import com.phlox.tvwebbrowser.utils.UpdateChecker
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.deleteDirectory
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivityViewModel: ActiveModel() {
    companion object {
        var TAG: String = MainActivityViewModel::class.java.simpleName
        const val WEB_VIEW_DATA_FOLDER = "app_webview"
        const val WEB_VIEW_CACHE_FOLDER = "WebView"
        const val WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX = "_backup"
        const val INCOGNITO_DATA_DIRECTORY_SUFFIX = "incognito"
    }

    var loaded = false
    var lastHistoryItem: HistoryItem? = null
    private var lastHistoryItemSaveJob: Job? = null
    val homePageLinks = ObservableList<HomePageLink>()
    val config = TVBro.config

    fun loadState() = modelScope.launch(Dispatchers.Main) {
        Log.d(TAG, "loadState")
        if (loaded) return@launch
        checkVersionCodeAndRunMigrations()
        initHistory()
        loadHomePageLinks()
        loaded = true
    }

    private suspend fun checkVersionCodeAndRunMigrations() {
        Log.d(TAG, "checkVersionCodeAndRunMigrations")
        if (config.appVersionCodeMark != BuildConfig.VERSION_CODE) {
            Log.i(TAG, "App version code changed from ${config.appVersionCodeMark} to ${BuildConfig.VERSION_CODE}")
            config.appVersionCodeMark = BuildConfig.VERSION_CODE
            withContext(Dispatchers.IO) {
                UpdateChecker.clearTempFilesIfAny(TVBro.instance)
            }
        }
    }

    private suspend fun initHistory() {
        Log.d(TAG, "initHistory")
        val count = AppDatabase.db.historyDao().count()
        if (count > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            AppDatabase.db.historyDao().deleteWhereTimeLessThan(c.time.time)
        }
        try {
            val result = AppDatabase.db.historyDao().last()
            if (result.isNotEmpty()) {
                lastHistoryItem = result[0]
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.recordException(e)
        }
    }

    private suspend fun loadHomePageLinks() {
        Log.d(TAG, "loadHomePageLinks")
        val config = TVBro.config
        if (config.homePageMode == Config.HomePageMode.HOME_PAGE) {
            when (config.homePageLinksMode) {
                Config.HomePageLinksMode.MOST_VISITED -> {
                    homePageLinks.replaceAll(
                        AppDatabase.db.historyDao().frequentlyUsedUrls()
                            .map { HomePageLink.fromHistoryItem(it) })
                }
                Config.HomePageLinksMode.LATEST_HISTORY -> {
                    homePageLinks.replaceAll(
                        AppDatabase.db.historyDao().last(8)
                            .map { HomePageLink.fromHistoryItem(it) })
                }
                Config.HomePageLinksMode.BOOKMARKS -> {
                    val favorites = ArrayList<FavoriteItem>()
                    favorites.addAll(AppDatabase.db.favoritesDao().getHomePageBookmarks())
                    val outdatedUsefulAffiliateLinks = favorites.filter { it.validUntil != null && it.validUntil!!.before(Date()) && it.useful }
                    outdatedUsefulAffiliateLinks.forEach {
                        it.validUntil = null
                        //destUrl if provided is affiliate link that can be outdated.
                        //With each affiliate link there also must be provided a direct link to the site
                        //so we can continue to use it after affiliate link is outdated if it was useful for user at least once
                        it.destUrl = null
                        AppDatabase.db.favoritesDao().update(it)
                    }
                    val hasOutdatedAffiliateLinks = favorites.any { it.validUntil != null && it.validUntil!!.before(Date())}
                    if ((favorites.isEmpty() && !config.initialBookmarksSuggestionsLoaded) || hasOutdatedAffiliateLinks) {
                        val suggestions = withContext(Dispatchers.IO) {
                            val countryCode = /*if (BuildConfig.DEBUG) "debug" else*/ try {
                                val response = URL("http://ip-api.com/json/").readText()
                                val jsonObject = JSONObject(response)
                                jsonObject.getString("countryCode")
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            } ?: Locale.getDefault().country ?: "default"

                            try {
                                val recommendationsUrl = "${Config.HOME_PAGE_URL}recommendations/$countryCode.json"
                                val response = URL(recommendationsUrl).readText()
                                val jsonArray = JSONArray(response)
                                val result = mutableListOf<FavoriteItem>()
                                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                for (i in 0 until jsonArray.length()) {
                                    val jsonObject = jsonArray.getJSONObject(i)
                                    val title = jsonObject.getString("title")
                                    val url = jsonObject.getString("url")
                                    val favicon = jsonObject.opt("favicon") as String?
                                    val destUrl = jsonObject.opt("dest_url") as String?
                                    val description = jsonObject.opt("description") as String?
                                    val validUntil = jsonObject.opt("valid_until") as String?
                                    val favorite = FavoriteItem()
                                    favorite.title = title
                                    favorite.url = url
                                    favorite.favicon = favicon
                                    favorite.destUrl = destUrl
                                    favorite.description = description
                                    favorite.order = i
                                    favorite.homePageBookmark = true
                                    if (validUntil != null) {
                                        favorite.validUntil = dateFormat.parse(validUntil)
                                    }
                                    result.add(favorite)
                                }
                                result
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }

                        if (suggestions != null) {
                            if (hasOutdatedAffiliateLinks) {
                                for (i in (favorites.size - 1) downTo 0) {
                                    val f = favorites[i]
                                    if (f.validUntil != null && f.validUntil!!.before(Date())) {
                                        AppDatabase.db.favoritesDao().delete(f)
                                        favorites.removeAt(i)
                                        val replacement = suggestions.find { it.order == f.order }
                                        if (replacement != null) {
                                            replacement.id = AppDatabase.db.favoritesDao().insert(replacement)
                                            favorites.add(i, replacement)
                                            continue
                                        }
                                    }
                                }
                            } else {
                                for (s in suggestions) {
                                    s.id = AppDatabase.db.favoritesDao().insert(s)
                                }
                                config.initialBookmarksSuggestionsLoaded = true
                            }
                            homePageLinks.replaceAll(suggestions.map { HomePageLink.fromBookmarkItem(it) })
                        }
                    } else {
                        homePageLinks.replaceAll(favorites.map { HomePageLink.fromBookmarkItem(it) })
                    }
                }
            }
        }
    }

    fun logVisitedHistory(title: String?, url: String, faviconHash: String?) {
        Log.d(TAG, "logVisitedHistory: $url")
        if ((url == lastHistoryItem?.url) || url == Config.HOME_PAGE_URL || !url.startsWith("http", true)) {
            return
        }

        val now = System.currentTimeMillis()
        val minVisitedInterval = 5000L //5 seconds

        lastHistoryItem?.let {
            if ((!it.saved) && (it.time + minVisitedInterval) > now) {
                lastHistoryItemSaveJob?.cancel()
            }
        }

        val item = HistoryItem()
        item.url = url
        item.title = title ?: ""
        item.time = now
        item.favicon = faviconHash
        lastHistoryItem = item
        lastHistoryItemSaveJob = modelScope.launch(Dispatchers.Main) {
            delay(minVisitedInterval)
            item.id = AppDatabase.db.historyDao().insert(item)
            item.saved = true
        }
    }

    fun onTabTitleUpdated(tab: WebTabState) {
        Log.d(TAG, "onTabTitleUpdated: ${tab.url} ${tab.title}")
        if (TVBro.config.incognitoMode) return
        val lastHistoryItem = lastHistoryItem ?: return
        if (tab.url == lastHistoryItem.url) {
            lastHistoryItem.title = tab.title
            if (lastHistoryItem.saved) {
                modelScope.launch(Dispatchers.Main) {
                    AppDatabase.db.historyDao().updateTitle(lastHistoryItem.id, lastHistoryItem.title)
                }
            }
        }
    }

    fun prepareSwitchToIncognito() {
        Log.d(TAG, "prepareSwitchToIncognito")
        if (TVBro.config.isWebEngineGecko()) return
        //to isolate incognito mode data:
        //in api >= 28 we just use another directory for WebView data
        //on earlier apis we backup-ing existing WebView data directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val incognitoWebViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
            )
            if (incognitoWebViewData.exists()) {
                Log.i(TAG, "Looks like we already in incognito mode")
                return
            }
            WebView.setDataDirectorySuffix(INCOGNITO_DATA_DIRECTORY_SUFFIX)
        } else {
            val webViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER
            )
            val backupedWebViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            if (backupedWebViewData.exists()) {
                Log.i(TAG, "Looks like we already in incognito mode")
                return
            }
            webViewData.renameTo(backupedWebViewData)
            val webViewCache =
                File(TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER)
            val backupedWebViewCache = File(
                TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER +
                        WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            webViewCache.renameTo(backupedWebViewCache)
        }
    }

    fun clearIncognitoData() = modelScope.launch(Dispatchers.IO) {
        Log.d(TAG, "clearIncognitoData")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val webViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
            )
            deleteDirectory(webViewData)
            var webViewCache =
                File(
                    TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER +
                            "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
                )
            if (!webViewCache.exists()) {
                webViewCache = File(
                    TVBro.instance.cacheDir.absolutePath + "/" +
                            WEB_VIEW_CACHE_FOLDER.lowercase(Locale.getDefault()) +
                            "_" + INCOGNITO_DATA_DIRECTORY_SUFFIX
                )
            }
            deleteDirectory(webViewCache)
        } else {
            val webViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER
            )
            deleteDirectory(webViewData)
            val webViewCache =
                File(TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER)
            deleteDirectory(webViewCache)

            val backupedWebViewData = File(
                TVBro.instance.filesDir.parentFile!!.absolutePath +
                        "/" + WEB_VIEW_DATA_FOLDER + WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            backupedWebViewData.renameTo(webViewData)
            val backupedWebViewCache = File(
                TVBro.instance.cacheDir.absolutePath + "/" + WEB_VIEW_CACHE_FOLDER +
                        WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX
            )
            backupedWebViewCache.renameTo(webViewCache)
        }
    }

    override fun onClear() {

    }

    fun removeHomePageLink(bookmark: HomePageLink) = modelScope.launch {
        homePageLinks.remove(bookmark)
        bookmark.favoriteId?.let {
            AppDatabase.db.favoritesDao().delete(it)
        }
    }

    fun onHomePageLinkEdited(item: FavoriteItem) = modelScope.launch {
        if (item.id == 0L) {
            val lastInsertRowId = AppDatabase.db.favoritesDao().insert(item)
            item.id = lastInsertRowId
            homePageLinks.add(HomePageLink.fromBookmarkItem(item))
        } else {
            AppDatabase.db.favoritesDao().update(item)
            val index = homePageLinks.indexOfFirst { it.favoriteId == item.id }
            if (index != -1) {
                homePageLinks[index] = HomePageLink.fromBookmarkItem(item)
            }
        }
    }

    fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
        val link = homePageLinks.find { it.order == bookmarkOrder } ?: return
        if (link.favoriteId == null || link.validUntil == null) return
        modelScope.launch {
            AppDatabase.db.favoritesDao().markAsUseful(link.favoriteId)
        }
    }
}