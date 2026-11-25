package com.phlox.tvwebbrowser.browser

import android.net.Uri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.phlox.tvwebbrowser.browser.sitefilter.AppSiteFilter
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
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
import com.phlox.tvwebbrowser.webengine.DataClearing
import com.phlox.tvwebbrowser.webengine.DataProfile
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.webengine.SiteDataKind
import com.phlox.tvwebbrowser.webengine.SiteView
import com.phlox.tvwebbrowser.webengine.TabEngineEvents
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.PageZoom
import com.phlox.tvwebbrowser.webengine.PageZoomSupport
import com.phlox.tvwebbrowser.webengine.pageZoom
import com.phlox.tvwebbrowser.webengine.siteView
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
import kotlin.math.roundToInt

/**
 * Browser core: owns the tabs (their list, the current one, their persistence) and
 * performs navigation in them. Knows nothing about the UI that shows the tabs:
 * the UI attaches itself as [TabViewHost].
 */
class BrowserController : ActiveModel() {
    companion object {
        var TAG: String = BrowserController::class.java.simpleName
        /** The engine file loads in tens of milliseconds; a slow storage must not keep the tabs waiting */
        private const val AD_BLOCK_LOAD_TIMEOUT_MS = 1000L
        private const val HISTORY_TRIM_INTERVAL_MS = 24 * 60 * 60 * 1000L
        /** The url of a tab that shows no page */
        const val BLANK_URL = "about:blank"
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
    /**
     * The site view of sites without their own: the activity sets it from the interface it shows
     * (tablet on TV, mobile on phones)
     */
    @Volatile
    var interfaceSiteView = SiteView.TABLET

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
    /** When the old visits were deleted last ([SystemClock.elapsedRealtime]) */
    private var historyTrimmedAt = 0L

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
        //engines ask for them synchronously as soon as pages start loading
        hostConfigs.loadSiteSettings()
        //the ad blocking engine of its file (a build from the lists goes on in the background)
        TVBro.container.adBlock.awaitLoaded(AD_BLOCK_LOAD_TIMEOUT_MS)
        //and the site filter, before the tabs of the kids profile load
        if (AppContext.kidsProfile) TVBro.container.siteFilter.awaitLoaded(AD_BLOCK_LOAD_TIMEOUT_MS)
        //tabs of the previous activity instance may be still saving
        writes.awaitPending()
        tabsStates.replaceAll(data.tabs.load(config.incognitoMode).map(BrowserTab::fromEntity))
        loaded = true
    }

    /** The site filter blocks the page of [url] now */
    fun isBlockedSite(url: String): Boolean =
        WebEngineFactory.siteFilter?.check(Uri.parse(url), true, null) != null

    /**
     * A parent let the site of [url] in through the site filter, for good or until [until] (a time); the [tab]
     * loads the page again
     */
    fun allowSite(tab: BrowserTab, url: String, until: Long) {
        val host = Uri.parse(url).host?.takeIf { it.isNotEmpty() } ?: return
        val filter = WebEngineFactory.siteFilter as? AppSiteFilter ?: return
        val site = filter.siteOf(host)
        val write = TVBro.container.appScope.launch {
            hostConfigs.setSiteAllowed(site, System.currentTimeMillis(), until)
        }
        modelScope.launch {
            write.join()
            if (tab.isWebEngineCreated) tab.webEngine.loadUrl(url)
        }
    }

    /** The process of a TV may live for weeks: the old visits go at start and then once a day */
    private fun trimHistoryDaily() {
        val now = SystemClock.elapsedRealtime()
        if (now - historyTrimmedAt < HISTORY_TRIM_INTERVAL_MS) return
        historyTrimmedAt = now
        modelScope.launch { data.history.trim(config.historyRetention) }
    }

    private suspend fun initHistory() {
        Log.d(TAG, "initHistory")
        historyTrimmedAt = SystemClock.elapsedRealtime()
        data.history.trim(config.historyRetention)
        try {
            //its title changes when the page shown again gets one
            historyLogger.lastHistoryItem = data.history.last(1).firstOrNull()?.also { it.saved = true }
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

    /** Removes the tab, its engine, its row and its files without selecting another tab */
    fun onCloseTab(tab: BrowserTab) = removeTabs(listOf(tab))

    private fun removeTabs(tabs: List<BrowserTab>) {
        for (tab in tabs) {
            if (tab.isWebEngineCreated) {
                tab.webEngine.onDetachFromWindow(completely = true, destroyTab = true)
            }
        }
        val removed = tabs.toSet()
        tabsStates.removeAll(removed)
        for (tab in tabsStates) {
            if (tab.openerTab in removed) tab.openerTab = null
        }
        writes.enqueue {
            for (tab in tabs) {
                //the id and the file names are final here: earlier writes of the tab are done
                val stateFile = tab.wvStateFileName
                val thumbnailName = tab.thumbnailHash
                if (tab.id != 0L) {
                    data.tabs.delete(tab.id)
                }
                withContext(Dispatchers.IO) { tabFiles.removeFiles(stateFile, thumbnailName) }
            }
        }
    }

    fun onCloseAllTabs(): Job {
        //of the tabs shown now, whatever the setting is when the write runs
        val incognito = config.incognitoMode
        val tabsClone = ArrayList(tabsStates)
        for (tab in tabsClone) {
            if (tab.isWebEngineCreated) {
                tab.webEngine.onDetachFromWindow(completely = true, destroyTab = true)
            }
        }
        tabsStates.clear()
        return writes.enqueue { deleteTabs(tabsClone, incognito) }
    }

    private suspend fun deleteTabs(tabsClone: List<BrowserTab>, incognito: Boolean) {
        val files = tabsClone.map { it.wvStateFileName to it.thumbnailHash }
        data.tabs.deleteAll(incognito)
        withContext(Dispatchers.IO) {
            files.forEach { (stateFile, thumbnailName) -> tabFiles.removeFiles(stateFile, thumbnailName) }
        }
    }

    /**
     * The model is released (the activity finished, or another space takes the screen): the engines of the tabs
     * are destroyed (GeckoView closes their sessions), the tabs stay saved
     */
    override fun onClear() {
        currentTab.value = null
        for (tab in tabsStates) {
            if (tab.isWebEngineCreated) {
                tab.webEngine.onDetachFromWindow(completely = true, destroyTab = true)
            }
        }
        tabsStates.clear()
        viewHost = null
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
     * Adds a tab at [index] without showing it: its page loads when it is shown, like a restored tab.
     */
    fun addBackgroundTab(url: String, title: String, index: Int): BrowserTab {
        val tab = BrowserTab(url = url, title = title, incognito = config.incognitoMode)
        tabsStates.add(index.coerceIn(0, tabsStates.size), tab)
        saveTab(tab, withFiles = false)
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
        Log.d(TAG, "openWindowTab: index: $index")
        tab.openerTab = currentTab
        tabsStates.add(index, tab)
        changeTab(tab)
        return webView
    }

    /**
     * Closes the tab. If it was the current one, the tab that takes its place (the next one, or the previous
     * one if it was the last) becomes current. Closing the only tab opens a new one with the home page.
     */
    fun closeTab(tab: BrowserTab) {
        val position = tabsStates.indexOf(tab)
        if (position == -1) return
        val wasCurrent = currentTab.value == tab
        if (wasCurrent) {
            currentTab.value = null
        }
        when {
            tabsStates.size == 1 -> openInNewTab(config.homePage, 0, navigateImmediately = true)

            !wasCurrent -> {}

            position < tabsStates.size - 1 -> changeTab(tabsStates[position + 1])

            else -> changeTab(tabsStates[position - 1])
        }
        onCloseTab(tab)
    }

    /**
     * Closes all tabs but [tab], which becomes the current one.
     */
    fun closeOtherTabs(tab: BrowserTab) {
        if (tab !in tabsStates) return
        changeTab(tab)
        removeTabs(tabsStates.filter { it != tab })
    }

    /**
     * Moves the tab by [offset] places (negative to the start).
     * @return false if the tab can not move that far
     */
    fun moveTab(tab: BrowserTab, offset: Int): Boolean {
        val from = tabsStates.indexOf(tab)
        if (from == -1) return false
        val to = from + offset
        if (offset == 0 || to < 0 || to >= tabsStates.size) return false
        tabsStates.move(from, to)
        return true
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
            tab.certificateErrorHost = null
            publishState()
            tab.url = url
            applySiteView(tab)
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

    fun stopLoading() {
        val tab = currentTab.value ?: return
        tab.webEngine.stopLoading()
        //not every engine reports the stopped page as finished
        tab.loading = false
        publishState()
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
     * Whether ads are blocked on the tab's current host: its own setting or the global one.
     * Can be called from any thread.
     */
    fun isAdBlockEnabled(tab: BrowserTab): Boolean {
        val host = hostNameOf(tab) ?: return config.adBlockEnabled
        return hostConfigs.adBlockSetting(host) ?: config.adBlockEnabled
    }

    /**
     * Turns ads blocking on or off for the tab's current host and reloads the tab.
     * A host keeps its own setting only while it differs from the global one.
     * @return the write of the setting; null if the tab's url has no host
     */
    fun setSiteAdBlock(tab: BrowserTab, enabled: Boolean): Job? {
        val host = hostNameOf(tab) ?: return null
        val setting = if (enabled == config.adBlockEnabled) null else enabled
        //the in-memory setting changes right away (the scope dispatches immediately on the main thread),
        //the write is not cancelled with the model
        val write = TVBro.container.appScope.launch { hostConfigs.setAdBlock(host, setting) }
        if (tab.isWebEngineCreated && tab == currentTab.value) {
            tab.webEngine.reload()
        }
        publishState()
        return write
    }

    /**
     * The view pages of the [url]'s host load in: its own setting or the default.
     * Can be called from any thread.
     */
    fun siteViewOf(url: String): SiteView {
        val host = hostNameOf(url) ?: return defaultSiteView
        return hostConfigs.siteViewSetting(host) ?: defaultSiteViewOf(host)
    }

    /** The site view of sites without their own: the setting, or the one of the interface */
    val defaultSiteView: SiteView get() = config.defaultSiteView ?: interfaceSiteView

    /** The view of the [host] while it has no setting of its own: that of [SiteViewDefaults], else the default */
    fun defaultSiteViewOf(host: String): SiteView = SiteViewDefaults.siteView(host) ?: defaultSiteView

    /**
     * Whether the videos that pages of the [host] put in fullscreen go to the built-in player: its own setting
     * ([builtInPlayerSetting]), else the default ([com.phlox.tvwebbrowser.Config.builtInPlayerByDefault])
     */
    fun prefersBuiltInPlayer(host: String): Boolean =
        builtInPlayerSetting(host) ?: config.builtInPlayerByDefault

    /** The player of fullscreen videos the [host] chose: true the built-in one, false its own, null the default */
    fun builtInPlayerSetting(host: String): Boolean? =
        if (host.isEmpty()) null else hostConfigs.builtInPlayerSetting(host)

    /**
     * Sets the player of fullscreen videos of the [host], null for the default; [prefersBuiltInPlayer] gives it at
     * once. @return the write, which is not cancelled with the model
     */
    fun setBuiltInPlayer(host: String, builtInPlayer: Boolean?): Job {
        //the in-memory setting changes right away (the scope runs at once on the main thread)
        val write = TVBro.container.appScope.launch { hostConfigs.setBuiltInPlayer(host, builtInPlayer) }
        publishState()
        return write
    }

    /**
     * Sets the player of fullscreen videos of the [host] as the site settings choose it: a host keeps its own
     * setting only while it differs from the default one (as with [setSiteView]). @return the write
     */
    fun setSitePlayer(host: String, builtIn: Boolean): Job =
        setBuiltInPlayer(host, if (builtIn == config.builtInPlayerByDefault) null else builtIn)

    /** The default player of fullscreen videos changed: the site settings show it */
    fun onDefaultPlayerChanged() = publishState()

    /**
     * Tabs get the new default view or User-Agent (settings of all sites); the current tab loads again if its view
     * changed
     */
    fun onSiteViewSettingsChanged() {
        for (tab in tabsStates) {
            if (applySiteView(tab) && tab == currentTab.value) {
                tab.webEngine.reload()
            }
        }
        publishState()
    }

    /**
     * Sets the site view of the tab's current host and reloads the tab if its view changes.
     * A host keeps its own setting only while it differs from the default one.
     * @return the write of the setting; null if the tab's url has no host
     */
    fun setSiteView(tab: BrowserTab, view: SiteView): Job? {
        val host = hostNameOf(tab) ?: return null
        val setting = if (view == defaultSiteViewOf(host)) null else view
        //the in-memory setting changes right away, the write is not cancelled with the model
        val write = TVBro.container.appScope.launch { hostConfigs.setSiteView(host, setting) }
        if (applySiteView(tab) && tab == currentTab.value) {
            tab.webEngine.reload()
        }
        publishState()
        return write
    }

    /**
     * Zooms the page of the tab out: as a pinch does while the user zoomed in past the width of the page, else to
     * the next smaller page zoom of its host ([PageZoom]), which the page gets at once and the next pages of the
     * host when they load. [onZoom] is told the zoom in percent after it (also when the page zoom is the smallest
     * already): the page zoom times that of the pinch, which the engine tells once it has settled.
     */
    fun zoomOut(tab: BrowserTab, onZoom: (Int) -> Unit) {
        val engine = tab.webEngine
        val support = engine.pageZoom
        val host = pageHostOf(tab)
        if (support == null || host == null) {
            engine.zoomOut()
            return
        }
        support.isZoomedIn { zoomedIn ->
            if (zoomedIn) {
                zoomVisually(support, host, zoomIn = false, onZoom)
                return@isZoomedIn
            }
            val zoom = PageZoom.smaller(pageZoomOf(host))
            if (zoom != null) setPageZoom(tab, host, zoom)
            onZoom(zoom ?: pageZoomOf(host))
        }
    }

    /**
     * Zooms the page of the tab in: to the next bigger page zoom of its host while it is below 100%, else as a
     * pinch does. [onZoom] is told the zoom in percent after it (see [zoomOut]).
     */
    fun zoomIn(tab: BrowserTab, onZoom: (Int) -> Unit) {
        val engine = tab.webEngine
        val support = engine.pageZoom
        val host = pageHostOf(tab)
        if (support == null || host == null) {
            engine.zoomIn()
            return
        }
        val zoom = PageZoom.bigger(pageZoomOf(host))
        if (zoom == null) {
            zoomVisually(support, host, zoomIn = true, onZoom)
            return
        }
        setPageZoom(tab, host, zoom)
        onZoom(zoom)
    }

    /** The pinch of the engine, told as the page zoom of the [host] times its own (the page may not tell it) */
    private fun zoomVisually(support: PageZoomSupport, host: String, zoomIn: Boolean, onZoom: (Int) -> Unit) {
        support.zoomVisually(zoomIn) { visualZoom ->
            if (visualZoom != null) onZoom((pageZoomOf(host) * visualZoom).roundToInt())
        }
    }

    /** The host of the page the engine shows (its scripts ask for the zoom of that one) */
    private fun pageHostOf(tab: BrowserTab): String? =
        hostNameOf((if (tab.isWebEngineCreated) tab.webEngine.url else null) ?: tab.url)

    private fun pageZoomOf(host: String): Int = hostConfigs.pageZoomSetting(host) ?: PageZoom.DEFAULT

    private fun setPageZoom(tab: BrowserTab, host: String, zoom: Int) {
        //the in-memory setting changes right away, the write is not cancelled with the model
        TVBro.container.appScope.launch { hostConfigs.setPageZoom(host, zoom.takeIf { it != PageZoom.DEFAULT }) }
        tab.webEngine.pageZoom?.applyPageZoom(zoom)
    }

    /**
     * Gives the engine of the tab the view of the host of [url] (its page by default; for a window a page opened
     * that has none yet, the page of its opener, whose site it mostly shows, so its first page does not start
     * again in another view) and the User-Agent of the user (it takes effect with the next load).
     * @return true if the view changed
     */
    private fun applySiteView(tab: BrowserTab, url: String = tab.url.ifEmpty { tab.openerTab?.url.orEmpty() }): Boolean {
        if (!tab.isWebEngineCreated) return false
        val siteView = tab.webEngine.siteView ?: return false
        val view = siteViewOf(url)
        val userAgent = config.userAgent
        if (siteView.view == view && siteView.userAgentOverride == userAgent) return false
        siteView.view = view
        siteView.userAgentOverride = userAgent
        return true
    }

    /**
     * Removes the [kinds] of data of the tab's site (cookies, stored data, cache; its settings and history
     * stay) and reloads the tab.
     */
    suspend fun clearSiteData(tab: BrowserTab, kinds: Set<SiteDataKind> = SiteDataKind.ALL) {
        if (kinds.isEmpty()) return
        val host = hostNameOf(tab) ?: return
        WebEngineFactory.clearSiteData(AppContext.get(), host, kinds)
        if (tab == currentTab.value && tab.isWebEngineCreated) {
            tab.webEngine.reload()
        }
    }

    /**
     * Clears the kinds of browsing data [chosen] for each profile ([BrowsingDataChoice]; [shown] is the profile of
     * the tabs shown): the history, the bookmarks, the site settings and the tabs of the main and of the kids
     * profile in their databases (the tabs shown are closed and a new one opens), the rows of the downloads that
     * ended, and the data of all sites in the engine ([WebEngineFactory.clearBrowsingData]; once for the profiles it
     * clears together). The current tab reloads when the cookies or the site settings of its pages went.
     */
    suspend fun clearBrowsingData(chosen: Map<DataProfile, Set<BrowsingDataKind>>, shown: DataProfile) {
        val container = TVBro.container
        var reload = false
        for ((profile, kinds) in chosen) {
            //the incognito mode keeps its bookmarks, downloads and site settings in the main profile
            val stored = when (profile) {
                DataProfile.MAIN -> container.mainData
                DataProfile.KIDS -> container.kidsData
                DataProfile.INCOGNITO -> null
            } ?: continue
            if (BrowsingDataKind.HISTORY in kinds) {
                if (profile == DataProfile.MAIN) historyLogger.forget()
                stored.history.deleteAll()
            }
            if (BrowsingDataKind.BOOKMARKS in kinds) {
                stored.favorites.deleteAll()
                if (stored === data) homePageLinks.load()
            }
            //the rows of the downloads of both profiles are in the database of the main one
            if (BrowsingDataKind.DOWNLOADS in kinds) {
                if (profile == DataProfile.KIDS) container.mainData.downloads.deleteEndedOfKids()
                else container.mainData.downloads.deleteEndedOfMain()
            }
            if (BrowsingDataKind.SITE_SETTINGS in kinds) {
                stored.hostConfigs.resetSiteSettings()
                if (stored === data) reload = true
            }
        }
        var together = false
        for ((profile, kinds) in chosen) {
            val siteData = kinds.flatMapTo(HashSet()) { it.siteData }
            if (siteData.isEmpty()) continue
            val clearing = WebEngineFactory.browsingDataClearing(profile)
            if (clearing == DataClearing.NONE || (clearing == DataClearing.WITH_OTHERS && together)) continue
            together = together || clearing == DataClearing.WITH_OTHERS
            WebEngineFactory.clearBrowsingData(AppContext.get(), profile, siteData)
            val clearsShown = profile == shown ||
                (clearing == DataClearing.WITH_OTHERS && WebEngineFactory.browsingDataClearing(shown) == clearing)
            if (clearsShown && BrowsingDataKind.SITE_DATA in kinds) reload = true
        }
        for ((profile, kinds) in chosen) {
            if (BrowsingDataKind.TABS !in kinds) continue
            if (profile == shown) {
                closeAllTabs()
                reload = false
            } else {
                deleteStoredTabs(profile)
            }
        }
        if (reload) currentTab.value?.takeIf { it.isWebEngineCreated }?.webEngine?.reload()
    }

    /**
     * Closes all the tabs shown and opens a new one with the home page; its save is queued after the deletion
     * ([writes] runs on the main thread: waiting for it there would never end)
     */
    private fun closeAllTabs() {
        currentTab.value = null
        onCloseAllTabs()
        openInNewTab(config.homePage, 0, navigateImmediately = true)
    }

    /** Deletes the saved tabs of a profile not shown, with their files */
    private fun deleteStoredTabs(profile: DataProfile) {
        val container = TVBro.container
        val kids = profile == DataProfile.KIDS
        val stored = if (kids) container.kidsData else container.mainData
        val files = if (kids) container.kidsTabFiles else container.mainTabFiles
        val incognito = profile == DataProfile.INCOGNITO
        writes.enqueue {
            val tabs = stored.tabs.load(incognito)
            stored.tabs.deleteAll(incognito)
            withContext(Dispatchers.IO) {
                for (tab in tabs) files.removeFiles(tab.wvStateFileName, tab.thumbnailHash)
            }
        }
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
                //its engine will not report the end of the loading
                loading = false
                webEngine.onDetachFromWindow(completely = false, destroyTab = false)
                onPause()
                saveTab(this@apply)
            }

            newTab.selected = true
            currentTab.value = newTab
        }
        var wv = newTab.webEngine.getView()
        var needReloadUrl = false
        val needsRestore = wv == null
        if (wv == null) {
            wv = host.createTabView(newTab)
            if (wv == null) {
                return
            }
        }
        //before the page is restored or loaded; a shown page whose view changed meanwhile loads again
        val siteViewChanged = applySiteView(newTab)
        if (needsRestore) {
            needReloadUrl = !tabFiles.restoreEngineState(newTab)
            if (!needReloadUrl) newTab.pageUrl = newTab.url
        }
        newTab.webEngine.onAttachToWindow(engineCallback(newTab, host), host.tabsContainer)
        if (needReloadUrl) {
            newTab.webEngine.loadUrl(HomePageUrl.resolve(newTab.url, config))
        } else if (siteViewChanged && !needsRestore) {
            newTab.webEngine.reload()
        }
        newTab.webEngine.setNetworkAvailable(Utils.isNetworkConnected(AppContext.get()))
        if (switched) {
            //the selection (and a new tab) is kept even if the process is killed without a pause;
            //its files did not change: the engine state was just restored from them
            saveTab(newTab, withFiles = false)
            //the site settings of a page that is not loaded again
            modelScope.launch {
                loadHostConfig(newTab)
                publishState()
            }
        }
    }

    /**
     * Null for urls without a host (about:home and the like) and for the built-in home page, which is
     * not a site; asked for every request, so it does not log
     */
    private fun hostNameOf(tab: BrowserTab): String? = hostNameOf(tab.url)

    private fun hostNameOf(url: String): String? =
        if (url.startsWith(Config.HOME_PAGE_URL)) null else url.toUri().host?.takeIf { it.isNotEmpty() }

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
        publishState()
    }

    private fun engineCallback(tab: BrowserTab, host: TabViewHost): WebEngineCallbacks {
        val policies = TabPolicies(tab, this)
        val uiHost = host.uiHost(tab)
        val tabUiHost = object : EngineUiHost by uiHost {
            override fun onDownloadRequested(request: DownloadRequest) {
                onDownloadOfTab(tab)
                uiHost.onDownloadRequested(request)
            }
        }
        return WebEngineCallbacks(TabEvents(tab, policies), policies, tabUiHost)
    }

    /**
     * A navigation that became a download leaves the page as it was: the tab gets back the url of the page it
     * shows, or about:blank if it showed none yet (a tab opened for a download link). Else it would keep the
     * link and download it again whenever it is restored or reloaded.
     */
    private fun onDownloadOfTab(tab: BrowserTab) {
        if (tab.url == tab.pageUrl) return
        tab.url = tab.pageUrl ?: BLANK_URL
        if (tab == currentTab.value) publishState()
        saveTab(tab, withFiles = false)
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
            host = hostNameOf(tab) ?: "",
            title = tab.title,
            loading = tab.loading,
            canGoBack = tab.webEngine.canGoBack(),
            canGoForward = tab.webEngine.canGoForward(),
            adBlockEnabled = isAdBlockEnabled(tab),
            blockedAds = tab.blockedAds,
            blockedPopups = tab.blockedPopups,
            popupBlockLevel = findHostConfig(tab)?.popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE,
            connection = connectionOf(tab),
            siteViewAvailable = tab.webEngine.siteView != null,
            siteView = siteViewOf(tab.url),
            siteViewIsDefault = hostNameOf(tab)?.let { hostConfigs.siteViewSetting(it) } == null,
            builtInPlayer = prefersBuiltInPlayer(hostNameOf(tab) ?: ""),
            builtInPlayerIsDefault = hostNameOf(tab)?.let { hostConfigs.builtInPlayerSetting(it) } == null,
            siteDataClearable = WebEngineFactory.supportsSiteDataClearing()
        )
    }

    private fun connectionOf(tab: BrowserTab): SiteConnection {
        val host = hostNameOf(tab) ?: return SiteConnection.NONE
        val uri = tab.url.toUri()
        return when {
            host == tab.certificateErrorHost -> SiteConnection.CERTIFICATE_ERROR
            uri.scheme.equals("https", ignoreCase = true) -> SiteConnection.SECURE
            uri.scheme.equals("http", ignoreCase = true) -> SiteConnection.HTTP
            else -> SiteConnection.NONE
        }
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
        /** The page is being loaded again in the view of its host */
        private var siteViewCorrected = false

        override fun onOpenInNewTabRequested(url: String, navigateImmediately: Boolean): WebEngine? {
            val newTab = openInNewTabAfterCurrent(url, navigateImmediately) ?: return null
            newTab.openerTab = tab
            sendUiEvent(BrowserUiEvent.TabOpenedByPage)
            return newTab.webEngine
        }

        override fun onProgressChanged(newProgress: Int) {
            sendUiEvent(BrowserUiEvent.Progress(newProgress))
            //also catches up with a page that was loading when the tab was shown
            val loading = newProgress < 100
            if (tab.loading != loading) {
                tab.loading = loading
                publishState()
            }
        }

        override fun onReceivedTitle(title: String) {
            tab.title = title
            sendUiEvent(BrowserUiEvent.TabUpdated(tab))
            if (!config.incognitoMode) {
                //the address of the page now: tab.url is not changed by redirects and history.pushState
                historyLogger.onTitleUpdated(tab.webEngine.url ?: tab.url, title)
            }
            publishState()
        }

        override fun onReceivedIcon(icon: Bitmap) {
            //the icon is of the page shown now: tab.url is not changed by redirects and history.pushState
            val url = tab.webEngine.url ?: tab.url
            modelScope.launch {
                //shown at once, then again if the page has a bigger one
                FaviconsPool.onEngineIcon(url, icon) { sendUiEvent(BrowserUiEvent.TabUpdated(tab)) }
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
            tab.pageUrl = tab.url
        }

        override fun onPageStarted(url: String?) {
            updateUrl(url)
            //a certificate error stays with its host (pages without a host, like the warning, keep it too)
            val host = hostNameOf(tab)
            if (host != null && host != tab.certificateErrorHost) tab.certificateErrorHost = null
            tab.blockedAds = 0
            tab.blockedPopups = 0
            tab.loading = true
            //a page that came in the view of another host (going back, a redirect) loads again in its own,
            //once: hosts redirecting to each other by the view must not reload forever. The view is that of the
            //page that starts: GeckoView still tells the page before it as its url until the new one commits, and
            //a reload then would load that page again in place of the new one; so does WebView (58 at least) for
            //a redirect to another host (Google's /url? links), so such a page is loaded by its url
            if (!siteViewCorrected && applySiteView(tab, url ?: tab.url)) {
                siteViewCorrected = true
                val engineUrl = tab.webEngine.url
                if (url != null && engineUrl != null && engineUrl != url) {
                    tab.webEngine.loadUrl(url)
                } else {
                    tab.webEngine.reload()
                }
            } else {
                siteViewCorrected = false
            }
            publishState()
            //for the popup blocking of this page
            modelScope.launch {
                loadHostConfig(tab)
                //the site settings shown for the page
                publishState()
            }
        }

        override fun onPageFinished(url: String?) {
            tab.loading = false
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
            tab.certificateErrorHost = url?.toUri()?.host?.takeIf { it.isNotEmpty() } ?: hostNameOf(tab)
            publishState()
        }

        override fun onEngineViewLost() {
            //only the current tab gets the callbacks; the others get a new view when they are shown
            val host = viewHost ?: return
            if (currentTab.value != tab) return
            tab.loading = false
            if (host.createTabView(tab) == null) return
            applySiteView(tab)
            tab.webEngine.onAttachToWindow(engineCallback(tab, host), host.tabsContainer)
            tab.webEngine.setNetworkAvailable(Utils.isNetworkConnected(AppContext.get()))
            publishState()
        }

        override fun onBlockedAd(uri: String) {
            //the engine may still report requests blocked before the setting changed
            if (!policies.isAdBlockingEnabled()) return
            tab.blockedAds++
            publishState()
        }

        override fun onAskToAllowSite(url: String) {
            //only for a page the filter blocks: an ordinary page can not make the PIN come up
            if (isBlockedSite(url)) sendUiEvent(BrowserUiEvent.AskToAllowSite(tab, url))
        }

        override fun onBlockedDialog(newTab: Boolean) {
            tab.blockedPopups++
            publishState()
            sendUiEvent(BrowserUiEvent.PopupBlocked(newTab))
        }

        override fun onPopupPageBlocked() {
            Log.d(TAG, "onPopupPageBlocked")
            if (tab !in tabsStates) return
            //the opener first, so that closing the popup shows no other tab on the way
            val opener = tab.openerTab?.takeIf { it in tabsStates }
            if (opener != null) changeTab(opener)
            closeTab(tab)
            if (opener != null) opener.blockedPopups++
            publishState()
            sendUiEvent(BrowserUiEvent.PopupBlocked(true))
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): View? {
            Log.d(TAG, "onCreateWindow: dialog: $dialog, userGesture: $userGesture")
            if (policies.shouldBlockNewWindow(dialog, userGesture)) {
                onBlockedDialog(!dialog)
                return null
            }
            return openWindowTab()
        }

        override fun closeWindow(internalRepresentation: Any) {
            val tabToClose = findTabBySession(internalRepresentation) ?: return
            Log.d(TAG, "closeWindow: ${tabToClose.url}")
            //a window the page closes (a popup of a sign-in) gives the screen back to the page that opened it
            val opener = tabToClose.openerTab?.takeIf { it in tabsStates }
            if (opener != null && currentTab.value == tabToClose) changeTab(opener)
            closeTab(tabToClose)
            sendUiEvent(BrowserUiEvent.TabClosedByPage)
        }

        override fun onVisited(url: String) {
            //the block page of the site filter shows at the address it blocks: no visit
            if (isBlockedSite(url)) return
            if (!config.incognitoMode) {
                trimHistoryDaily()
                //the title of the tab may still be the one of the previous page
                historyLogger.logVisited(url) {
                    //asked later: the tab may be closed by then (its engine released)
                    if (tab.isWebEngineCreated && !tab.loading && tab.webEngine.url == url) tab.title else null
                }
            }
        }

        override fun getHomePageLinks(): List<HomePageLink> = homePageLinks.links.value

        override fun onHomePageSearchBarLocated(x: Int, y: Int) {
            sendUiEvent(BrowserUiEvent.HomePageSearchBarLocated(tab, x, y))
        }
    }
}
