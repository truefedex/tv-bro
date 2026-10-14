package com.phlox.tvwebbrowser.activity.main

import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.browser.VisitedHistoryLogger
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.UpdateChecker
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.deleteDirectory
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivityViewModel: ActiveModel() {
    companion object {
        var TAG: String = MainActivityViewModel::class.java.simpleName
        const val WEB_VIEW_DATA_FOLDER = "app_webview"
        const val WEB_VIEW_CACHE_FOLDER = "WebView"
        const val WEB_VIEW_DATA_BACKUP_DIRECTORY_SUFFIX = "_backup"
        const val INCOGNITO_DATA_DIRECTORY_SUFFIX = "incognito"
    }

    var loaded = false
    private val historyLogger = VisitedHistoryLogger(
        CoroutineScope(modelScope.coroutineContext + Dispatchers.Main),
        object : VisitedHistoryLogger.Store {
            override suspend fun insert(item: HistoryItem): Long =
                AppDatabase.db.historyDao().insert(item)

            override suspend fun updateTitle(id: Long, title: String) =
                AppDatabase.db.historyDao().updateTitle(id, title)
        })
    val homePageLinks = ObservableList<HomePageLink>()
    val config = AppContext.provideConfig()

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
                historyLogger.lastHistoryItem = result[0]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadHomePageLinks() {
        Log.d(TAG, "loadHomePageLinks")
        val config = AppContext.provideConfig()
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
                    if ((favorites.isEmpty() && !config.initialBookmarksSuggestionsLoaded)) {
                        //There was loading of default bookmarks from app's home page (with affiliate links).
                        // Now its just hardcoded. I left them as json array just for convenience to copy the same
                        // data used in corresponding home html page where them useful for debug
                        val suggestions =
                            try {
                                val jsonArray = JSONArray("""[
        {"title": "Wikipedia - the free encyclopedia", "url":"https://www.wikipedia.org", "favicon": "https://ru.wikipedia.org/static/apple-touch/wikipedia.png"},
        {"title": "Reddit - Dive into anything", "url":"https://www.reddit.com", "favicon": "https://www.redditstatic.com/desktop2x/img/favicon/apple-icon-120x120.png"},
        {"title": "My Tuner", "url":"https://mytuner-radio.com/", "favicon": "https://static2.mytuner.mobi/static/icons/apple-touch-icon-120x120-precomposed.png"},
        {"title": "Instagram", "url":"https://www.instagram.com", "favicon": "https://static.cdninstagram.com/rsrc.php/v3/ys/r/aM-g435MtEX.png"},
        {"title": "IMDb", "url":"https://www.imdb.com/", "favicon": "https://m.media-amazon.com/images/G/01/imdb/images-ANDW73HA/favicon_iPhone_retina_180x180._CB1582158069_UX60_.png"},
        {"title": "eBay", "url":"https://www.ebay.com/", "favicon": "https://www.ebay.com/apple-touch-icon.png"},
        {"title": "Itch.io", "url":"https://itch.io/games/input-gamepad/platform-web", "favicon": "https://itch.io/static/images/itchio-square-144.png"},
        {"title": "BBC News", "url":"https://www.bbc.com/news", "favicon": "https://www.bbc.com/apple-touch-icon-120x120-precomposed.png"}
    ]""")
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


                        if (suggestions != null) {
                            for (s in suggestions) {
                                s.id = AppDatabase.db.favoritesDao().insert(s)
                            }
                            config.initialBookmarksSuggestionsLoaded = true

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
        historyLogger.logVisited(title, url, faviconHash)
    }

    fun onTabTitleUpdated(tab: WebTabState) {
        Log.d(TAG, "onTabTitleUpdated: ${tab.url} ${tab.title}")
        if (AppContext.provideConfig().incognitoMode) return
        historyLogger.onTitleUpdated(tab.url, tab.title)
    }

    fun prepareSwitchToIncognito() {
        Log.d(TAG, "prepareSwitchToIncognito")
        if (AppContext.provideConfig().isWebEngineGecko()) return
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
        val favoriteId = link.favoriteId ?: return
        if (link.validUntil == null) return
        modelScope.launch {
            AppDatabase.db.favoritesDao().markAsUseful(favoriteId)
        }
    }
}