package com.phlox.tvwebbrowser.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.webengine.TabEngineEvents
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Calendar

/**
 * Browser core: owns the tabs (their list, the current one, their persistence) and
 * performs navigation in them. Knows nothing about the UI that shows the tabs:
 * the UI attaches itself as [TabViewHost].
 */
class BrowserController : ActiveModel() {
    companion object {
        var TAG: String = BrowserController::class.java.simpleName
    }

    /**
     * The UI that shows the current tab: creates engine views and holds them.
     */
    interface TabViewHost {
        val tabsContainer: ViewGroup

        /** Creates the view of the tab's engine; null if the engine can not create it */
        fun createTabView(tab: WebTabState): View?

        /** What the engine of the tab needs from the UI */
        fun uiHost(tab: WebTabState): EngineUiHost
    }

    /** Set while an activity shows the tabs */
    var viewHost: TabViewHost? = null
    var adBlocker: AdBlocker = object : AdBlocker {
        override fun isAd(url: Uri, type: String?, baseUri: Uri) = false
    }

    private val _currentTabState = MutableStateFlow(CurrentTabState())
    val currentTabState: StateFlow<CurrentTabState> = _currentTabState.asStateFlow()

    private val uiEventsChannel = Channel<BrowserUiEvent>(Channel.UNLIMITED)
    /** One-time events for the UI; should have a single collector */
    val uiEvents: Flow<BrowserUiEvent> = uiEventsChannel.receiveAsFlow()
    private val mainHandler = Handler(Looper.getMainLooper())

    var loaded = false
    val currentTab = ObservableValue<WebTabState?>(null)
    val tabsStates = ObservableList<WebTabState>()
    private val config = AppContext.provideConfig()
    private var incognitoMode = config.incognitoMode
    //all tabs writes go through this queue: they are not cancelled with the model and do not overlap
    private val writes = TVBro.instance.container.tabWrites
    val homePageLinks = HomePageLinks(config)
    private val historyLogger = VisitedHistoryLogger(modelScope, object : VisitedHistoryLogger.Store {
        override suspend fun insert(item: HistoryItem): Long = AppDatabase.db.historyDao().insert(item)
        override suspend fun updateTitle(id: Long, title: String) = AppDatabase.db.historyDao().updateTitle(id, title)
    })
    private var historyLoaded = false

    init {
        currentTab.subscribe({ publishState() }, false)
        tabsStates.subscribe({
            //auto-update positions on any list change
            var positionsChanged = false
            tabsStates.forEachIndexed { index, webTabState ->
                if (webTabState.position != index) {
                    webTabState.position = index
                    positionsChanged = true
                }
            }
            if (positionsChanged) {
                val tabsListClone = listOf(*tabsStates.toTypedArray())
                writes.enqueue {
                    val tabsDao = AppDatabase.db.tabsDao()
                    tabsDao.updatePositions(tabsListClone)
                }
            }
        }, false)
    }

    fun loadState() = modelScope.launch(Dispatchers.Main) {
        if (!historyLoaded) {
            initHistory()
            homePageLinks.load()
            historyLoaded = true
        }
        if (loaded) {
            //check is incognito mode changed
            if (incognitoMode != config.incognitoMode) {
                incognitoMode = config.incognitoMode
                loaded = false
            } else {
                return@launch
            }
        }
        //tabs of the previous activity instance may be still saving
        writes.awaitPending()
        val tabsDao = AppDatabase.db.tabsDao()
        tabsStates.replaceAll(tabsDao.getAll(config.incognitoMode))
        loaded = true
    }

    private suspend fun initHistory() {
        Log.d(TAG, "initHistory")
        val historyDao = AppDatabase.db.historyDao()
        if (historyDao.count() > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            historyDao.deleteWhereTimeLessThan(c.time.time)
        }
        try {
            historyLogger.lastHistoryItem = historyDao.last().firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveTab(tab: WebTabState) = writes.enqueue {
        val tabsDB = AppDatabase.db.tabsDao()
        if (tab.selected) {
            tabsDB.unselectAll(config.incognitoMode)
        }
        withContext(Dispatchers.IO) {
            tab.saveWebViewStateToFile()
        }
        if (tab.id != 0L) {
            tabsDB.update(tab)
        } else {
            tab.id = tabsDB.insert(tab)
        }
    }

    fun onCloseTab(tab: WebTabState) {
        tab.webEngine.onDetachFromWindow(completely = true, destroyTab = true)
        tabsStates.remove(tab)
        writes.enqueue {
            val tabsDB = AppDatabase.db.tabsDao()
            tabsDB.delete(tab)
            withContext(Dispatchers.IO) { tab.removeFiles() }
        }
    }

    fun onCloseAllTabs(): Job {
        val tabsClone = ArrayList(tabsStates)
        for (tab in tabsClone) {
            if (tab.isWebEngineCreated) {
                tab.webEngine.onDetachFromWindow(completely = true, destroyTab = true)
            }
        }
        tabsStates.clear()
        return writes.enqueue { deleteTabs(tabsClone) }
    }

    private suspend fun deleteTabs(tabsClone: List<WebTabState>) {
        val tabsDB = AppDatabase.db.tabsDao()
        tabsDB.deleteAll(config.incognitoMode)
        withContext(Dispatchers.IO) {
            tabsClone.forEach { it.removeFiles() }
        }
    }

    fun onDetachActivity(host: TabViewHost) {
        for (tab in tabsStates) {
            if (tab.isWebEngineCreated) {
                tab.webEngine.onDetachFromWindow(completely = true, destroyTab = false)
            }
        }
        if (viewHost === host) {
            viewHost = null
        }
    }

    fun onActivityResume() {
        currentTab.value?.webEngine?.onResume()
    }

    fun onActivityPause() {
        currentTab.value?.apply {
            webEngine.onPause()
            onPause()
            saveTab(this@apply)
        }
    }

    fun trimMemory() {
        for (tab in tabsStates) {
            if (!tab.selected) {
                tab.trimMemory()
            }
        }
    }

    /**
     * Opens the tab that was selected last time, or a new tab with the home page if there are no tabs.
     */
    fun selectStartTab() {
        if (tabsStates.isEmpty()) {
            openInNewTab(config.homePage, 0, navigateImmediately = true)
            return
        }
        //no selected tab may happen in some error states
        changeTab(tabsStates.firstOrNull { it.selected } ?: tabsStates[0])
    }

    /**
     * @return the new tab or null if its engine view can not be created
     */
    fun openInNewTab(url: String, index: Int, navigateImmediately: Boolean): WebTabState? {
        Log.d(TAG, "openInNewTab: url: $url, index: $index, navigateImmediately: $navigateImmediately")
        val host = viewHost ?: return null
        val tab = WebTabState(url = url, incognito = config.incognitoMode)
        host.createTabView(tab) ?: return null
        tabsStates.add(index, tab)
        changeTab(tab)
        if (navigateImmediately) {
            navigate(url)
        }
        return tab
    }

    /**
     * Opens a new tab right after the current one (at the end if there is no current tab).
     */
    fun openInNewTabAfterCurrent(url: String, navigateImmediately: Boolean): WebTabState? {
        var index = tabsStates.indexOf(currentTab.value)
        index = if (index == -1) tabsStates.size else index + 1
        return openInNewTab(url, index, navigateImmediately)
    }

    /**
     * Opens an empty tab right after the current one for a window the page has opened.
     * @return the engine view of the new tab
     */
    fun openWindowTab(): View? {
        val host = viewHost ?: return null
        val tab = WebTabState(incognito = config.incognitoMode)
        val webView = host.createTabView(tab) ?: return null
        val currentTab = currentTab.value ?: return null
        val index = tabsStates.indexOf(currentTab) + 1
        tabsStates.add(index, tab)
        changeTab(tab)
        return webView
    }

    /**
     * Closes the tab and selects a neighbour one. Closing the last tab opens a new one with the home page.
     */
    fun closeTab(tab: WebTabState) {
        val position = tabsStates.indexOf(tab)
        if (currentTab.value == tab) {
            currentTab.value = null
        }
        when {
            tabsStates.size == 1 -> openInNewTab(config.homePage, 0, navigateImmediately = true)

            position > 0 -> changeTab(tabsStates[position - 1])

            else -> changeTab(tabsStates[position + 1])
        }
        onCloseTab(tab)
    }

    fun findTabBySession(internalRepresentation: Any): WebTabState? =
        tabsStates.firstOrNull { it.webEngine.isSameSession(internalRepresentation) }

    /**
     * Loads the url in the current tab or in a new tab if there is no current one.
     */
    fun navigate(url: String) {
        Log.d(TAG, "navigate: $url")
        val tab = currentTab.value
        if (tab != null) {
            tab.certificateError = false
            publishState()
            tab.url = url
            tab.webEngine.loadUrl(HomePageUrl.resolve(url, config))
        } else {
            openInNewTab(url, 0, navigateImmediately = true)
        }
    }

    /**
     * @return false if the current tab has no history to go back
     */
    fun goBack(): Boolean {
        val tab = currentTab.value ?: return false
        if (!tab.webEngine.canGoBack()) return false
        tab.webEngine.goBack()
        return true
    }

    fun goForward() {
        val tab = currentTab.value ?: return
        if (tab.webEngine.canGoForward()) {
            tab.webEngine.goForward()
        }
    }

    fun reload() {
        currentTab.value?.webEngine?.reload()
    }

    /**
     * Toggles ads blocking for the current tab (overriding the global setting).
     * @return the changed tab
     */
    fun toggleAdBlockForCurrentTab(): WebTabState? {
        val tab = currentTab.value ?: return null
        val newState = !(tab.adblock ?: config.adBlockEnabled)
        tab.adblock = newState
        tab.webEngine.onUpdateAdblockSetting(newState)
        publishState()
        return tab
    }

    fun changeTab(newTab: WebTabState) {
        val host = viewHost ?: return
        if (currentTab.value == newTab && newTab.webEngine.getView() != null) return
        if (currentTab.value != newTab) {
            tabsStates.forEach {
                it.selected = false
            }
            currentTab.value?.apply {
                webEngine.onDetachFromWindow(completely = false, destroyTab = false)
                onPause()
                saveTab(this@apply)
            }

            newTab.selected = true
            currentTab.value = newTab
        }
        var wv = newTab.webEngine.getView()
        var needReloadUrl = false
        if (wv == null) {
            wv = host.createTabView(newTab)
            if (wv == null) {
                return
            }
            needReloadUrl = !newTab.restoreWebView()
        }
        newTab.webEngine.onAttachToWindow(engineCallback(newTab, host), host.tabsContainer)
        if (needReloadUrl) {
            newTab.webEngine.loadUrl(HomePageUrl.resolve(newTab.url, config))
        }
        newTab.webEngine.setNetworkAvailable(Utils.isNetworkConnected(TVBro.instance))
    }

    private fun hostNameOf(tab: WebTabState): String? = try {
        URL(tab.url).host
    } catch (e: Exception) {
        Log.w(TAG, "Can not parse current url host: $e")
        null
    }

    /**
     * Finds the config of the tab's current host. On a cache miss queries the database
     * synchronously (it is a lookup by an indexed column).
     */
    fun findHostConfig(tab: WebTabState): HostConfig? {
        Log.d(TAG, "findHostConfig")
        val currentHostName = hostNameOf(tab) ?: return null
        var hostConfig = tab.cachedHostConfig
        if (hostConfig == null || hostConfig.hostName != currentHostName) {
            hostConfig = AppDatabase.db.hostsDao().findByHostName(currentHostName)
            tab.cachedHostConfig = hostConfig
        }
        return hostConfig
    }

    private suspend fun findOrCreateHostConfig(tab: WebTabState): HostConfig? {
        findHostConfig(tab)?.let { return it }
        val currentHostName = hostNameOf(tab) ?: return null
        val hostConfig = HostConfig(currentHostName)
        hostConfig.id = AppDatabase.db.hostsDao().insert(hostConfig)
        tab.cachedHostConfig = hostConfig
        return hostConfig
    }

    suspend fun changePopupBlockingLevel(newLevel: Int, tab: WebTabState) {
        val hostConfig = findOrCreateHostConfig(tab) ?: return
        hostConfig.popupBlockLevel = newLevel
        AppDatabase.db.hostsDao().update(hostConfig)
    }

    private fun engineCallback(tab: WebTabState, host: TabViewHost): WebEngineCallbacks {
        val policies = TabPolicies(tab, this, adBlocker, config)
        return WebEngineCallbacks(TabEvents(tab, policies), policies, host.uiHost(tab))
    }

    private fun sendUiEvent(event: BrowserUiEvent) {
        uiEventsChannel.trySend(event)
    }

    /**
     * Updates [currentTabState] from the current tab. Can be called from any thread.
     */
    private fun publishState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { publishState() }
            return
        }
        val tab = currentTab.value
        _currentTabState.value = if (tab == null) CurrentTabState() else CurrentTabState(
            tabIdentity = System.identityHashCode(tab),
            hasTab = true,
            url = tab.url,
            title = tab.title,
            canGoBack = tab.webEngine.canGoBack(),
            canGoForward = tab.webEngine.canGoForward(),
            adBlockEnabled = tab.adblock ?: config.adBlockEnabled,
            blockedAds = tab.blockedAds,
            blockedPopups = tab.blockedPopups,
            certificateError = tab.certificateError
        )
    }

    private fun needsLocalNetworkPermission(url: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            Utils.isLocalNetworkUrl(url) &&
            ContextCompat.checkSelfPermission(AppContext.get(), Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED

    /**
     * What the engine of the [tab] reports. The engine calls it only while the tab is the current one.
     */
    private inner class TabEvents(val tab: WebTabState, val policies: TabPolicies) : TabEngineEvents {
        override fun onOpenInNewTabRequested(url: String, navigateImmediately: Boolean): WebEngine? {
            val newTab = openInNewTabAfterCurrent(url, navigateImmediately) ?: return null
            sendUiEvent(BrowserUiEvent.TabOpenedByPage)
            return newTab.webEngine
        }

        override fun onProgressChanged(newProgress: Int) {
            sendUiEvent(BrowserUiEvent.Progress(newProgress))
        }

        override fun onReceivedTitle(title: String) {
            tab.title = title
            sendUiEvent(BrowserUiEvent.TabUpdated(tab))
            if (!config.incognitoMode) {
                historyLogger.onTitleUpdated(tab.url, tab.title)
            }
            publishState()
        }

        override fun onReceivedIcon(icon: Bitmap) {
            val url = tab.url
            modelScope.launch {
                FaviconsPool.putIfAbsent(url, icon, persist = !config.incognitoMode)
                sendUiEvent(BrowserUiEvent.TabUpdated(tab))
            }
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            tab.lastLoadingUrl = url

            when (UrlRouter.route(url, ::needsLocalNetworkPermission)) {
                UrlRouter.UrlRoute.BLOCK -> return true
                UrlRouter.UrlRoute.LOAD -> return false
                UrlRouter.UrlRoute.REQUEST_LOCAL_NETWORK_PERMISSION -> {
                    sendUiEvent(BrowserUiEvent.LocalNetworkPermissionNeeded)
                    return true
                }
                UrlRouter.UrlRoute.OPEN_INTENT_URI -> {
                    sendUiEvent(BrowserUiEvent.OpenInExternalApp(url))
                    return true
                }
                UrlRouter.UrlRoute.OPEN_IN_EXTERNAL_APP -> {}
            }

            //try to handle intent for non-network urls by external apps
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(AppContext.get().packageManager) != null) {
                    sendUiEvent(BrowserUiEvent.AskToOpenInExternalApp(url, intent))
                } else {
                    Log.d(TAG, "shouldOverrideUrlLoading: no activity to handle intent")
                    sendUiEvent(BrowserUiEvent.NoAppToOpenUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "shouldOverrideUrlLoading: ", e)
            }
            return true
        }

        private fun updateUrl(url: String?) {
            val engineUrl = tab.webEngine.url
            if (engineUrl != null) {
                tab.url = engineUrl
            } else if (url != null) {
                tab.url = url
            }
        }

        override fun onPageStarted(url: String?) {
            updateUrl(url)
            tab.blockedAds = 0
            tab.blockedPopups = 0
            publishState()
        }

        override fun onPageFinished(url: String?) {
            if (currentTab.value == null) {
                return
            }
            updateUrl(url)
            publishState()

            //keep only the thumbnail of this tab in memory
            tabsStates.onEach { if (it != tab) it.thumbnail = null }
            modelScope.launch {
                val newThumbnail = tab.webEngine.renderThumbnail(tab.thumbnail)
                if (newThumbnail != null) {
                    tab.updateThumbnail(AppContext.get(), newThumbnail)
                    sendUiEvent(BrowserUiEvent.ThumbnailUpdated(tab))
                }
            }
        }

        override fun onPageCertificateError(url: String?) {
            tab.certificateError = true
            publishState()
        }

        override fun onBlockedAd(uri: String) {
            Log.i(TAG, "onBlockedAd: $uri")
            if (!config.adBlockEnabled) return
            tab.blockedAds++
            publishState()
        }

        override fun onBlockedDialog(newTab: Boolean) {
            tab.blockedPopups++
            publishState()
            sendUiEvent(BrowserUiEvent.PopupBlocked(newTab))
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): View? {
            if (policies.shouldBlockNewWindow(dialog, userGesture)) {
                onBlockedDialog(!dialog)
                return null
            }
            return openWindowTab()
        }

        override fun closeWindow(internalRepresentation: Any) {
            val tabToClose = findTabBySession(internalRepresentation) ?: return
            closeTab(tabToClose)
            sendUiEvent(BrowserUiEvent.TabClosedByPage)
        }

        override fun onScaleChanged(oldScale: Float, newScale: Float) {
            Log.d(TAG, "onScaleChanged: oldScale: $oldScale newScale: $newScale")
            tab.scale = newScale
        }

        override fun onVisited(url: String) {
            if (!config.incognitoMode) {
                historyLogger.logVisited(tab.title, url, tab.faviconHash)
            }
        }

        override fun getHomePageLinks(): List<HomePageLink> = homePageLinks.links.value

        override fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
            modelScope.launch { homePageLinks.markRecommendationAsUseful(bookmarkOrder) }
        }
    }
}
