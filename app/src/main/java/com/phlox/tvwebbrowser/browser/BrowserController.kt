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
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.TabEntity
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
        fun createTabView(tab: BrowserTab): View?

        /** What the engine of the tab needs from the UI */
        fun uiHost(tab: BrowserTab): EngineUiHost
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
    val currentTab = ObservableValue<BrowserTab?>(null)
    val tabsStates = ObservableList<BrowserTab>()
    private val config = AppContext.provideConfig()
    private var incognitoMode = config.incognitoMode
    //all tabs writes go through this queue: they are not cancelled with the model and do not overlap
    private val writes = TVBro.container.tabWrites
    private val tabFiles = TVBro.container.tabFiles
    private val data = TVBro.container.data
    private val hostConfigs = data.hostConfigs
    val homePageLinks = HomePageLinks(config, data.history, data.favorites)
    private val historyLogger = VisitedHistoryLogger(modelScope, data.history)
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
                writes.enqueue {
                    //ids of new tabs are known only when their inserts (enqueued earlier) are done
                    val positions = tabsStates.filter { it.id != 0L }.associate { it.id to it.position }
                    data.tabs.updatePositions(positions)
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
        tabsStates.replaceAll(data.tabs.load(config.incognitoMode).map(BrowserTab::fromEntity))
        loaded = true
    }

    private suspend fun initHistory() {
        Log.d(TAG, "initHistory")
        data.history.trim()
        try {
            historyLogger.lastHistoryItem = data.history.last(1).firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Stores the tab: its database row and, if [withFiles], its engine state and changed thumbnail.
     */
    fun saveTab(tab: BrowserTab, withFiles: Boolean = true): Job {
        //the tab as it is now: it may change (or drop its state and thumbnail) before the write runs
        val snapshot = tab.toEntity()
        val files = if (withFiles) {
            TabFilesSnapshot(
                state = tab.savedState,
                //an engine that was never created has no new state
                enginePrefix = if (tab.isWebEngineCreated) tab.webEngine.stateFilePrefix else null,
                thumbnail = tab.takeChangedThumbnail()
            )
        } else {
            null
        }
        return writes.enqueue { writeTab(tab, snapshot, files) }
    }

    private class TabFilesSnapshot(val state: Any?, val enginePrefix: String?, val thumbnail: Bitmap?)

    /**
     * Writes the [snapshot] of the tab with its id and file names, which are known only here:
     * the earlier writes of the tab give them. The write queue runs on the main thread, so the tab
     * is read and changed here, between the background writes of the files and of the database.
     */
    private suspend fun writeTab(tab: BrowserTab, snapshot: TabEntity, files: TabFilesSnapshot?) {
        if (files != null) {
            val oldStateFile = tab.wvStateFileName
            val oldThumbnail = tab.thumbnailHash
            val (stateFile, thumbnailName) = withContext(Dispatchers.IO) {
                val stateFile = files.enginePrefix?.let { tabFiles.writeEngineState(files.state, it, oldStateFile) }
                    ?: oldStateFile
                val thumbnailName = files.thumbnail?.let { tabFiles.writeThumbnail(it, oldThumbnail) } ?: oldThumbnail
                stateFile to thumbnailName
            }
            tab.wvStateFileName = stateFile
            tab.thumbnailHash = thumbnailName
        }

        val entity = snapshot.copy(id = tab.id, wvStateFileName = tab.wvStateFileName, thumbnailHash = tab.thumbnailHash)
        tab.id = data.tabs.save(entity)
    }

    fun onCloseTab(tab: BrowserTab) {
        if (tab.isWebEngineCreated) {
            tab.webEngine.onDetachFromWindow(completely = true, destroyTab = true)
        }
        tabsStates.remove(tab)
        writes.enqueue {
            //the id and the file names are final here: earlier writes of the tab are done
            val stateFile = tab.wvStateFileName
            val thumbnailName = tab.thumbnailHash
            if (tab.id != 0L) {
                data.tabs.delete(tab.id)
            }
            withContext(Dispatchers.IO) { tabFiles.removeFiles(stateFile, thumbnailName) }
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

    private suspend fun deleteTabs(tabsClone: List<BrowserTab>) {
        val files = tabsClone.map { it.wvStateFileName to it.thumbnailHash }
        data.tabs.deleteAll(config.incognitoMode)
        withContext(Dispatchers.IO) {
            files.forEach { (stateFile, thumbnailName) -> tabFiles.removeFiles(stateFile, thumbnailName) }
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

    /**
     * Loads the saved thumbnail of the tab if it has none in memory.
     */
    suspend fun loadThumbnail(tab: BrowserTab): Bitmap? {
        tab.thumbnail?.let { return it }
        val name = tab.thumbnailHash ?: return null
        val thumbnail = withContext(Dispatchers.IO) { tabFiles.loadThumbnail(name) }
        if (tab.thumbnail == null) {
            tab.thumbnail = thumbnail
        }
        return tab.thumbnail
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
    fun openInNewTab(url: String, index: Int, navigateImmediately: Boolean): BrowserTab? {
        Log.d(TAG, "openInNewTab: url: $url, index: $index, navigateImmediately: $navigateImmediately")
        val host = viewHost ?: return null
        val tab = BrowserTab(url = url, incognito = config.incognitoMode)
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
    fun openInNewTabAfterCurrent(url: String, navigateImmediately: Boolean): BrowserTab? {
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
        val tab = BrowserTab(incognito = config.incognitoMode)
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
    fun closeTab(tab: BrowserTab) {
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

    fun findTabBySession(internalRepresentation: Any): BrowserTab? =
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

    fun togglePlayback() {
        currentTab.value?.webEngine?.togglePlayback()
    }

    fun stopPlayback() {
        currentTab.value?.webEngine?.stopPlayback()
    }

    fun rewind() {
        currentTab.value?.webEngine?.rewind()
    }

    fun fastForward() {
        currentTab.value?.webEngine?.fastForward()
    }

    /**
     * Toggles ads blocking for the current tab (overriding the global setting).
     * @return the changed tab
     */
    fun toggleAdBlockForCurrentTab(): BrowserTab? {
        val tab = currentTab.value ?: return null
        val newState = !(tab.adblock ?: config.adBlockEnabled)
        tab.adblock = newState
        tab.webEngine.onUpdateAdblockSetting(newState)
        publishState()
        return tab
    }

    fun changeTab(newTab: BrowserTab) {
        val host = viewHost ?: return
        if (currentTab.value == newTab && newTab.webEngine.getView() != null) return
        val switched = currentTab.value != newTab
        if (switched) {
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
            needReloadUrl = !tabFiles.restoreEngineState(newTab)
        }
        newTab.webEngine.onAttachToWindow(engineCallback(newTab, host), host.tabsContainer)
        if (needReloadUrl) {
            newTab.webEngine.loadUrl(HomePageUrl.resolve(newTab.url, config))
        }
        newTab.webEngine.setNetworkAvailable(Utils.isNetworkConnected(AppContext.get()))
        if (switched) {
            //the selection (and a new tab) is kept even if the process is killed without a pause;
            //its files did not change: the engine state was just restored from them
            saveTab(newTab, withFiles = false)
        }
    }

    private fun hostNameOf(tab: BrowserTab): String? = try {
        URL(tab.url).host
    } catch (e: Exception) {
        Log.w(TAG, "Can not parse current url host: $e")
        null
    }

    /**
     * The config of the tab's current host as it is known now, without the database: it is loaded
     * when a page starts loading ([loadHostConfig]), until then it is null (the defaults apply).
     */
    fun findHostConfig(tab: BrowserTab): HostConfig? {
        val currentHostName = hostNameOf(tab) ?: return null
        return hostConfigs.cached(currentHostName)
    }

    suspend fun loadHostConfig(tab: BrowserTab): HostConfig? {
        val currentHostName = hostNameOf(tab) ?: return null
        return hostConfigs.find(currentHostName)
    }

    suspend fun changePopupBlockingLevel(newLevel: Int, tab: BrowserTab) {
        val currentHostName = hostNameOf(tab) ?: return
        hostConfigs.setPopupBlockLevel(currentHostName, newLevel)
    }

    private fun engineCallback(tab: BrowserTab, host: TabViewHost): WebEngineCallbacks {
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
    private inner class TabEvents(val tab: BrowserTab, val policies: TabPolicies) : TabEngineEvents {
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
            //for the popup blocking of this page
            modelScope.launch { loadHostConfig(tab) }
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
                    tab.updateThumbnail(newThumbnail)
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
            //the engine may still report requests blocked before the setting changed
            if (!policies.isAdBlockingEnabled()) return
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

        override fun onVisited(url: String) {
            if (!config.incognitoMode) {
                historyLogger.logVisited(tab.title, url)
            }
        }

        override fun getHomePageLinks(): List<HomePageLink> = homePageLinks.links.value

        override fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
            modelScope.launch { homePageLinks.markRecommendationAsUseful(bookmarkOrder) }
        }
    }
}
