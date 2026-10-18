package com.phlox.tvwebbrowser.activity.main

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.ClearDownloadsDialog
import com.phlox.tvwebbrowser.activity.main.view.PhoneBar
import com.phlox.tvwebbrowser.activity.main.view.PhoneMenuSheet
import com.phlox.tvwebbrowser.activity.main.view.PhoneTabSwitcher
import com.phlox.tvwebbrowser.activity.main.view.ScrollAwayBar
import com.phlox.tvwebbrowser.activity.main.view.SiteSettingsView
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.browser.DownloadEvent
import com.phlox.tvwebbrowser.browser.DownloadsModel
import com.phlox.tvwebbrowser.databinding.ActivityMainPhoneBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadFileActions
import com.phlox.tvwebbrowser.utils.SystemBars
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.webengine.virtualCursor
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.launch

/**
 * The phone look: touch only (no virtual cursor), the page between the system bars, any orientation.
 */
class PhoneShell(private val host: BrowserShell.Host) : BrowserShell {
    private val activity = host.activity
    /** Recognizes speech for the bookmarks search (not for the search of the bar, which shows the page) */
    private val bookmarksVoiceSearch = VoiceSearchHelper(activity)
    private val controller = host.controller
    private val vb = ActivityMainPhoneBinding.inflate(activity.layoutInflater)

    override val root: View get() = vb.root
    override val tabsContainer: ViewGroup get() = vb.flWebViewContainer
    override val screenOrientation: Int get() = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** A fullscreen view (a video) fills the whole window, without the bar */
    private var fullscreen = false
    /** The bar slides away while the page scrolls down */
    private val scrollAwayBar = ScrollAwayBar(vb.vPhoneBar, vb.flWebViewContainer)
    /** The page and its loading state shown last: a new page or a new load shows the bar */
    private var renderedUrl: String? = null
    private var renderedLoading = false

    init {
        //the window is drawn edge to edge: the content keeps out of the system bars and the keyboard,
        //the menu sheet out of the navigation bar (its scrim covers the bars), the tab switcher out of both;
        //a fullscreen view covers the whole window
        ViewCompat.setOnApplyWindowInsetsListener(vb.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (fullscreen) {
                vb.rlRoot.setPadding(0, 0, 0, 0)
            } else {
                vb.rlRoot.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            }
            vb.vMenuSheet.setInsets(bars.left, bars.right, bars.bottom)
            vb.vTabSwitcher.setInsets(bars.left, bars.top, bars.right, bars.bottom)
            vb.vBookmarks.setInsets(bars.left, bars.top, bars.right, bars.bottom, keyboard.bottom)
            vb.vDownloads.setInsets(bars.left, bars.top, bars.right, bars.bottom)
            vb.vActionsSheet.setInsets(bars.left, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        SystemBars.matchIconsToTheme(activity.window)
        //the page changes its size and scroll position when the screen rotates
        vb.root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) scrollAwayBar.show(animate = false)
        }
    }

    private val barCallback = object : PhoneBar.Callback {
        override fun navigateBack() {
            controller.goBack()
        }

        override fun reloadOrStop() {
            if (controller.currentTabState.value.loading) controller.stopLoading() else controller.reload()
        }

        //the site settings are in the menu sheet
        override fun showSiteSettings() = showMenuSheet()

        override fun showTabs() {
            vb.vPhoneBar.stopEditing()
            vb.vMenuSheet.hide()
            vb.vTabSwitcher.show()
        }

        override fun showMenu() = showMenuSheet()

        override fun search(text: String) = host.search(text)

        override fun onEditingChanged(editing: Boolean) {
            editingBackCallback.isEnabled = editing
            if (editing) scrollAwayBar.show()
        }
    }

    /** Ends the editing of the address (a first Back hides the keyboard) */
    private val editingBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = vb.vPhoneBar.stopEditing()
    }

    private val menuCallback = object : PhoneMenuSheet.Callback {
        override fun goForward() = controller.goForward()

        override fun openNewTab() {
            host.openInNewTab(host.settingsModel.homePage, controller.tabsStates.size)
        }

        override fun toggleIncognitoMode() = host.toggleIncognitoMode()

        override fun bookmarkPage() {
            activity.lifecycleScope.launch {
                val page = BookmarksNavigator.PageInfo.of(controller.currentTab.value) ?: return@launch
                val bookmark = TVBro.container.data.favorites.findByUrl(page.url)
                if (bookmark != null) vb.vBookmarks.showForm(bookmark) else vb.vBookmarks.addPage(page)
            }
        }

        override fun showHistory() = host.showHistory()

        override fun showBookmarks() = vb.vBookmarks.show()

        override fun showDownloads() = vb.vDownloads.show()

        override fun showSettings() = host.showSettings()
    }

    /** The Bookmark button of the sheet is selected on a page that is bookmarked */
    private fun showMenuSheet() {
        vb.vMenuSheet.setBookmarked(false)
        vb.vMenuSheet.show()
        val page = BookmarksNavigator.PageInfo.of(controller.currentTab.value) ?: return
        activity.lifecycleScope.launch {
            vb.vMenuSheet.setBookmarked(TVBro.container.data.favorites.findByUrl(page.url) != null)
        }
    }

    private val bookmarksCallback = object : BookmarksNavigator.ShellCallback {
        override fun currentPage() = BookmarksNavigator.PageInfo.of(controller.currentTab.value)

        override fun openBookmark(url: String) = host.navigate(url)

        override fun openBookmarkInNewTab(url: String) {
            host.openInNewTab(url, controller.tabsStates.size)
        }

        //the home page shows the bookmarks of its folder
        override fun onBookmarksChanged() {
            controller.modelScope.launch { controller.homePageLinks.load() }
        }

        override fun showMessage(icon: Int, message: String) {
            NotificationView.show(vb.rlRoot, icon, message)
        }

        override fun confirm(message: String, action: () -> Unit) {
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(R.string.delete) { _, _ -> action() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        override fun startVoiceSearch(onResult: (String) -> Unit) = bookmarksVoiceSearch.recognize(onResult)

        override val bookmarkFiles = DeviceBookmarkFiles(activity)
    }

    /** The switcher stays open when tabs are closed, it closes when a tab is chosen or opened */
    private val tabSwitcherCallback = object : PhoneTabSwitcher.Callback {
        override fun selectTab(tab: BrowserTab) {
            controller.changeTab(tab)
            vb.vTabSwitcher.hide()
        }

        override fun closeTab(tab: BrowserTab) = controller.closeTab(tab)

        /** Leaves one tab with the home page */
        override fun closeAllTabs() {
            val tab = host.openInNewTab(host.settingsModel.homePage, controller.tabsStates.size) ?: return
            controller.closeOtherTabs(tab)
        }

        override fun openNewTab() {
            host.openInNewTab(host.settingsModel.homePage, controller.tabsStates.size)
        }

        override fun toggleIncognitoMode() = host.toggleIncognitoMode()
    }

    private val downloadsModel = ActiveModelsRepository.get(DownloadsModel::class, activity)
    private val downloadDevice = DeviceDownloads(activity)

    private val downloadsCallback = object : DownloadsNavigator.ShellCallback {
        override val downloads get() = downloadsModel.downloads
        override val downloadCommands get() = host.downloadCommands
        override val downloadDevice get() = this@PhoneShell.downloadDevice

        override fun showMessage(icon: Int, message: String) {
            NotificationView.show(vb.rlRoot, icon, message)
        }

        override fun confirmClearAll(request: DownloadsNavigator.ClearAllRequest, onConfirm: (Boolean) -> Unit) =
            ClearDownloadsDialog.show(activity, request, onConfirm)
    }

    /** The snackbar tells when a download ends, with the action OK would do on its row */
    private fun onDownloadEvent(event: DownloadEvent) {
        if (vb.vDownloads.isOpen) return
        val item = event.item
        when (event) {
            is DownloadEvent.Completed -> {
                val apk = DownloadFileActions.isApk(item.toDownload())
                vb.vSnackbar.show(R.drawable.ic_check, activity.getString(R.string.download_complete), item.filename,
                    activity.getString(if (apk) R.string.install else R.string.open)) {
                    if (apk) downloadDevice.install(item) else downloadDevice.open(item)
                }
            }
            is DownloadEvent.Failed -> vb.vSnackbar.show(R.drawable.ic_error,
                activity.getString(R.string.download_failed_named, item.filename), null,
                activity.getString(R.string.show)) { vb.vDownloads.show(item.id) }
        }
    }

    /** The site settings keep the sheet open: changing ad blocking reloads the page under it */
    private val siteSettingsCallback = object : SiteSettingsView.Callback {
        override fun toggleAdBlock() = host.toggleSiteAdBlock()

        override fun choosePopupBlockLevel() = host.choosePopupBlockLevel()
    }

    //after the callbacks: properties are initialized in their order
    init {
        vb.vPhoneBar.callback = barCallback
        vb.vMenuSheet.callback = menuCallback
        vb.vMenuSheet.siteSettings.callback = siteSettingsCallback
        vb.vTabSwitcher.bind(controller)
        vb.vTabSwitcher.callback = tabSwitcherCallback
        vb.vBookmarks.attach(activity.lifecycleScope, vb.vActionsSheet, bookmarksCallback)
        vb.vDownloads.attach(activity.lifecycleScope, vb.vActionsSheet, downloadsCallback)
        activity.lifecycleScope.launch { downloadsModel.events.collect(::onDownloadEvent) }
        controller.tabsStates.subscribe(activity, observer = { vb.vPhoneBar.setTabCount(it.size) })
    }

    /** Lowest priority: back in the page, or out of the app (to the launcher) when it has no history */
    private val pageBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!controller.goBack()) {
                onNoBackHistory()
            }
        }
    }

    override fun registerBackCallbacks(dispatcher: OnBackPressedDispatcher, fullscreen: OnBackPressedCallback) {
        //the enabled callback added last goes first
        dispatcher.addCallback(activity, pageBackCallback)
        dispatcher.addCallback(activity, editingBackCallback)
        dispatcher.addCallback(activity, vb.vMenuSheet.backCallback)
        dispatcher.addCallback(activity, vb.vTabSwitcher.backCallback)
        dispatcher.addCallback(activity, vb.vBookmarks.backCallback)
        dispatcher.addCallback(activity, vb.vDownloads.backCallback)
        dispatcher.addCallback(activity, vb.vActionsSheet.backCallback)
        dispatcher.addCallback(activity, fullscreen)
    }

    /** Videos are mostly wide: fullscreen turns the screen to landscape (either way, whatever the rotation lock) */
    override fun onFullscreenChanged(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        if (fullscreen) {
            vb.vPhoneBar.stopEditing()
            vb.vMenuSheet.hide()
            vb.vTabSwitcher.hide()
            vb.vActionsSheet.hide()
            vb.vBookmarks.hide()
            vb.vDownloads.hide()
            vb.vSnackbar.hide()
            scrollAwayBar.show(animate = false)
        }
        vb.vPhoneBar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        activity.requestedOrientation =
            if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else screenOrientation
        ViewCompat.requestApplyInsets(vb.root)
    }

    override fun showBusy(busy: Boolean) {
        vb.progressBarGeneric.visibility = if (busy) View.VISIBLE else View.GONE
    }

    override fun onTabsLoaded() {}

    /** Touch only: the page gets the touches, the virtual cursor is never shown */
    override fun onTabViewCreated(tab: BrowserTab) {
        tab.webEngine.virtualCursor?.setVirtualCursorMode(false)
    }

    override fun render(state: CurrentTabState) {
        //another tab or page, or the page starts loading
        if (state.url != renderedUrl || (state.loading && !renderedLoading)) {
            scrollAwayBar.show()
        }
        renderedUrl = state.url
        renderedLoading = state.loading
        vb.vPhoneBar.render(state)
        vb.vMenuSheet.render(state)
    }

    /**
     * Only scrolling by the user moves the bar: the page also scrolls by itself (when it is laid out
     * again after a rotation, restores its position or jumps to an anchor)
     */
    override fun onPageScrolled(scrollY: Int, dy: Int) {
        if (fullscreen || editingBackCallback.isEnabled || !vb.flWebViewContainer.isTouchedRecently) return
        scrollAwayBar.onPageScrolled(scrollY, dy)
    }

    override fun onUiEvent(event: BrowserUiEvent) {
        when (event) {
            is BrowserUiEvent.Progress -> {
                vb.progressBar.visibility = if (event.value < 100) View.VISIBLE else View.GONE
                vb.progressBar.progress = event.value
            }
            is BrowserUiEvent.PopupBlocked -> {
                val msg = activity.getString(if (event.newTab) R.string.new_tab_blocked else R.string.popup_dialog_blocked)
                NotificationView.show(vb.rlRoot, R.drawable.ic_block_popups, msg)
            }
            else -> {}
        }
    }

    /** Phones have the system Back and Home: no history leaves the app without closing it */
    override fun onNoBackHistory() {
        activity.moveTaskToBack(true)
    }

    override fun showPage() {
        vb.vPhoneBar.stopEditing()
        vb.vMenuSheet.hide()
        vb.vTabSwitcher.hide()
        vb.vActionsSheet.hide()
        vb.vBookmarks.hide()
        vb.vDownloads.hide()
    }

    override fun onDownloadStarted(download: Download) {
        vb.vSnackbar.show(R.drawable.ic_downloads, activity.getString(R.string.downloading_named_file, download.filename),
            null, activity.getString(R.string.show)) { vb.vDownloads.show(download.id) }
    }

    override fun showAllDownloads(focusId: Long?) {
        vb.vMenuSheet.hide()
        vb.vDownloads.show(focusId)
    }

    /** A long press on a link: the actions for the link (there is no cursor menu) */
    override fun showContextMenu(
        tab: BrowserTab, uiHost: EngineUiHost, cursorDrawer: CursorDrawerDelegate,
        baseUri: String?, linkUri: String?, srcUri: String?,
        title: String?, altText: String?, textContent: String?, x: Int, y: Int
    ) {
        uiHost.suggestActionsForLink(baseUri, linkUri, srcUri, title, altText, textContent, x, y)
    }
}
