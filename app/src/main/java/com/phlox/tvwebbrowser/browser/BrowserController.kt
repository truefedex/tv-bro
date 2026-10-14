package com.phlox.tvwebbrowser.browser

import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        fun createTabView(tab: WebTabState): View?

        fun engineCallback(tab: WebTabState): WebEngineWindowProviderCallback

        /** Called before the current tab starts loading an url requested by the user or the app */
        fun onNavigate()
    }

    /** Set while an activity shows the tabs */
    var viewHost: TabViewHost? = null

    var loaded = false
    val currentTab = ObservableValue<WebTabState?>(null)
    val tabsStates = ObservableList<WebTabState>()
    private val config = AppContext.provideConfig()
    private var incognitoMode = config.incognitoMode
    //all tabs writes go through this queue: they are not cancelled with the model and do not overlap
    private val writes = TVBro.instance.container.tabWrites

    init {
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
            tab.webEngine.onDetachFromWindow(completely = true, destroyTab = false)
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
            viewHost?.onNavigate()
            tab.url = url
            tab.webEngine.loadUrl(url)
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
        newTab.webEngine.onAttachToWindow(host.engineCallback(newTab), host.tabsContainer)
        if (needReloadUrl) {
            newTab.webEngine.loadUrl(newTab.url)
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
}