package com.phlox.tvwebbrowser.activity.main

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.view.ActionBar
import com.phlox.tvwebbrowser.activity.main.view.SitePanelView
import com.phlox.tvwebbrowser.activity.main.view.TabMenuView
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.webengine.CursorModes
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.webengine.virtualCursor
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URL

/**
 * The TV look: the bar above the page (its row of buttons and the address, and the tabs; opened and
 * closed with Back), its panels and menus, and the virtual cursor.
 */
class TvShell(private val host: BrowserShell.Host) : BrowserShell, ActionBar.Callback {
    companion object {
        private val TAG = TvShell::class.java.simpleName
    }

    private val activity = host.activity
    private val controller = host.controller
    private val settingsModel = host.settingsModel
    private val lifecycleScope = activity.lifecycleScope
    private val uiHandler = Handler(Looper.getMainLooper())
    private val vb = ActivityMainBinding.inflate(activity.layoutInflater)

    override val root: View get() = vb.root
    override val tabsContainer: ViewGroup get() = vb.flWebViewContainer
    override val screenOrientation: Int get() = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    private val progressBarHideRunnable: Runnable = Runnable {
        val anim = AnimationUtils.loadAnimation(activity, android.R.anim.fade_out)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                vb.progressBar.visibility = View.GONE
            }
        })
        vb.progressBar.startAnimation(anim)
    }

    private val displayThumbnailRunnable = object : Runnable {
        var tabState: BrowserTab? = null
        override fun run() {
            tabState?.let {
                lifecycleScope.launch(Dispatchers.Main) {
                    displayThumbnail(it)
                }
            }
        }
    }

    private val tabsListener = object : Listener {
        override fun onTitleChanged(index: Int) {
            Log.d(TAG, "onTitleChanged: $index")
            val tab = tabByTitleIndex(index)
            vb.vActionBar.setAddressBoxText(tab?.url ?: "")
            uiHandler.removeCallbacks(displayThumbnailRunnable)
            displayThumbnailRunnable.tabState = tab
            uiHandler.postDelayed(displayThumbnailRunnable, 200)
        }

        override fun onTitleSelected(index: Int) {
            syncTabWithTitles()
            hideMenuOverlay()
        }

        override fun onAddNewTabSelected() {
            openHomeTab(controller.tabsStates.size)
        }

        override fun showTabMenu(tab: BrowserTab, view: View) {
            tabOptionsHintAnchor = null
            vb.llTabOptionsHint.visibility = View.GONE
            vb.vTabMenu.show(tab, view, controller.tabsStates.indexOf(tab), controller.tabsStates.size)
        }
    }

    private val tabMenuCallback = object : TabMenuView.Callback {
        override fun closeTab(tab: BrowserTab) = closeTabInPlace(tab)

        override fun closeOtherTabs(tab: BrowserTab) {
            //the kept tab becomes the current one; the bar stays open
            controller.closeOtherTabs(tab)
            vb.vTabs.post { vb.vTabs.focusTab(tab) }
        }

        override fun openNewTabToTheRight(tab: BrowserTab) {
            openHomeTab(controller.tabsStates.indexOf(tab) + 1)
        }

        override fun moveTab(tab: BrowserTab, offset: Int) {
            if (!controller.moveTab(tab, offset)) return
            //the tab keeps its view (and the focus) when it moves; the tab it traded places with stays in view
            vb.vTabs.post {
                vb.vTabs.focusTab(tab)
                vb.vTabs.scrollToShow(controller.tabsStates.indexOf(tab) - offset)
            }
        }
    }

    /** A tab opened with the home page, waiting for the page to put the cursor on its search bar */
    private var tabForSearchBarCursor: BrowserTab? = null

    /** Opens a tab with the home page at [index]; the bar hides, and the cursor goes to the search bar */
    private fun openHomeTab(index: Int) {
        tabForSearchBarCursor = host.openInNewTab(settingsModel.homePage, index)
    }

    /**
     * Closes the tab of the strip, not necessarily the current one. The bar stays open (to close more
     * tabs), and the focus goes to the tab that takes its place (the next one, the previous one if it was
     * the last), before the tab is removed: the strip loses the focus if the focused view is removed.
     */
    private fun closeTabInPlace(tab: BrowserTab) {
        val tabs = controller.tabsStates
        val index = tabs.indexOf(tab)
        if (index == -1) return
        val neighbour = tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1)
        if (neighbour != null) {
            vb.vTabs.focusTab(neighbour)
        }
        controller.closeTab(tab)
        //closing the only tab opens a new one with the home page
        val focusTarget = neighbour ?: controller.currentTab.value
        if (focusTarget != null) {
            vb.vTabs.post { vb.vTabs.focusTab(focusTarget) }
        }
        val name = tab.title.ifEmpty { tab.url }
        NotificationView.show(vb.rlRoot, R.drawable.ic_close, activity.getString(R.string.tab_closed, name))
    }

    /** The focused tab of the strip that the "Tab options" hint is under (Down opens its menu) */
    private var tabOptionsHintAnchor: View? = null

    private val tabOptionsHintFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val showHint = newFocus != null && newFocus.tag is BrowserTab && !vb.vTabMenu.isOpen && vb.rlActionBar.isVisible
        tabOptionsHintAnchor = if (showHint) newFocus else null
        placeTabOptionsHint()
    }

    /** The hint follows its tab when the tab moves or the strip scrolls */
    private val tabOptionsHintDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (tabOptionsHintAnchor != null) placeTabOptionsHint()
        true
    }

    private fun placeTabOptionsHint() {
        val hint = vb.llTabOptionsHint
        val anchor = tabOptionsHintAnchor
        if (anchor == null || !anchor.isAttachedToWindow || !anchor.hasFocus()) {
            tabOptionsHintAnchor = null
            hint.visibility = View.GONE
            return
        }
        val tabPosition = IntArray(2).also { anchor.getLocationInWindow(it) }
        val rootPosition = IntArray(2).also { vb.rlRoot.getLocationInWindow(it) }
        val x = (tabPosition[0] - rootPosition[0] + activity.resources.getDimensionPixelSize(R.dimen.ds_tab_hint_offset_x)).toFloat()
        val y = (tabPosition[1] - rootPosition[1] + anchor.height +
                activity.resources.getDimensionPixelSize(R.dimen.ds_tab_hint_offset_y)).toFloat()
        if (hint.translationX != x) hint.translationX = x
        if (hint.translationY != y) hint.translationY = y
        hint.visibility = View.VISIBLE
    }

    override fun closeWindow() = host.closeWindow()

    //the bar stays open: the page under it is updated when it finishes loading
    override fun navigateBack() {
        //a dimmed Back does nothing (Back of the remote opens and closes the bar)
        controller.goBack()
    }

    override fun navigateForward() = controller.goForward()

    override fun reloadOrStop() {
        if (controller.currentTabState.value.loading) {
            controller.stopLoading()
        } else {
            controller.reload()
        }
    }

    override fun showDownloads() = host.showDownloads()

    override fun showHistory() {
        host.showHistory()
        hideMenuOverlay()
    }

    override fun showFavorites() {
        host.showFavorites()
        hideMenuOverlay()
    }

    override fun showSettings() = host.showSettings()

    override fun showSitePanel() {
        //a page without a host has no site settings
        if (controller.currentTabState.value.host.isEmpty()) return
        vb.vSitePanel.show(vb.vActionBar.siteButton)
    }

    private val sitePanelCallback = object : SitePanelView.Callback {
        //the page reloads, the panel stays open with the focus on the row
        override fun toggleAdBlock() = toggleAdBlockForTab()

        override fun choosePopupBlockLevel() {
            showPopupBlockOptions()
        }
    }

    override fun initiateVoiceSearch() = host.commands.startVoiceSearch()

    override fun search(text: String) = host.search(text)

    override fun toggleIncognitoMode() = host.toggleIncognitoMode()

    //after the listeners: properties are initialized in their order
    init {
        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.rlActionBar.visibility = View.INVISIBLE
        vb.progressBar.visibility = View.GONE

        vb.vTabs.listener = tabsListener

        vb.vActionBar.callback = this
        vb.vSitePanel.callback = sitePanelCallback
        vb.vTabMenu.callback = tabMenuCallback
        vb.rlRoot.viewTreeObserver.addOnGlobalFocusChangeListener(tabOptionsHintFocusListener)
        vb.rlRoot.viewTreeObserver.addOnPreDrawListener(tabOptionsHintDrawListener)

        //TV screens do not rotate, but large screens may ignore the requested orientation:
        //the thumbnail under the open menu has the height of the screen it was shown on
        vb.flWebViewContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && vb.rlActionBar.isVisible) {
                vb.root.post { fitThumbnailUnderMenu() }
            }
        }
    }

    private fun fitThumbnailUnderMenu() {
        if (!vb.rlActionBar.isVisible || !vb.ivMiniatures.isVisible) return
        vb.ivMiniatures.layoutParams = vb.ivMiniatures.layoutParams.apply { height = vb.flWebViewContainer.height }
        vb.ivMiniatures.translationY = vb.rlActionBar.height.toFloat()
    }

    private fun tabByTitleIndex(index: Int) =
            if (index >= 0 && index < controller.tabsStates.size) controller.tabsStates[index] else null

    override fun onUrlInputDone() {
        hideMenuOverlay()
    }

    override fun showBusy(busy: Boolean) {
        vb.progressBarGeneric.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            vb.progressBarGeneric.requestFocus()
        }
    }

    override fun onTabViewCreated(tab: BrowserTab) {}

    override fun onTabsLoaded() {
        val currentTab = controller.currentTab.value
        if (currentTab == null || currentTab.url == settingsModel.homePage) {
            showMenuOverlay()
        }
    }

    override fun onNoBackHistory() {
        //no history: back to the menu
        if (vb.rlActionBar.visibility != View.VISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    override fun showPage() {
        if (vb.rlActionBar.visibility == View.VISIBLE) {
            hideMenuOverlay()
        }
    }

    override fun onDownloadStarted() {
        showMenuOverlay()
    }

    override fun showContextMenu(
        tab: BrowserTab, uiHost: EngineUiHost, cursorDrawer: CursorDrawerDelegate,
        baseUri: String?, linkUri: String?, srcUri: String?,
        title: String?, altText: String?, textContent: String?, x: Int, y: Int
    ) {
        vb.vCursorMenu.show(tab, uiHost, cursorDrawer, baseUri, linkUri, srcUri, title, altText, textContent, x, y)
    }

    private var renderedTabIdentity: Int? = null

    override fun render(state: CurrentTabState) {
        vb.vActionBar.setNavigationState(state.canGoBack, state.canGoForward, state.loading)

        vb.vActionBar.setSiteState(state.blockedAds + state.blockedPopups, hasSite = state.host.isNotEmpty())
        vb.vSitePanel.render(state)

        vb.vActionBar.setAddressBoxTextColor(if (state.certificateError) Color.RED else
            activity.themeColor(R.attr.tbColorDsInk))

        val tabChanged = state.tabIdentity != renderedTabIdentity
        renderedTabIdentity = state.tabIdentity
        if (tabChanged) {
            onRenderedTabChanged()
        }
        //the address is not touched while the user edits it or browses the tabs list
        //(then the address of the focused tab is shown)
        if (tabChanged || (!vb.vActionBar.isEditingAddress() &&
                    tabByTitleIndex(vb.vTabs.current) == controller.currentTab.value)) {
            vb.vActionBar.setAddressBoxText(state.url)
        }
    }

    private var cursorModesJob: Job? = null

    /**
     * Follows the cursor modes of the shown tab. The grab and direct navigation modes belong
     * to the page they were turned on for, so they end when another tab is shown.
     */
    private fun onRenderedTabChanged() {
        cursorModesJob?.cancel()
        cursorModesJob = null
        val cursor = controller.currentTab.value?.webEngine?.virtualCursor
        if (cursor == null) {
            onCursorModesChanged(CursorModes())
            return
        }
        if (!cursor.isVirtualCursorMode()) {
            cursor.setVirtualCursorMode(true)
        }
        cursor.getCursorDrawerDelegate()?.let {
            if (it.canHandleBackNavigation()) it.handleBackNavigation()
        }
        cursorModesJob = lifecycleScope.launch {
            cursor.cursorModes.collect { onCursorModesChanged(it) }
        }
    }

    private fun onCursorModesChanged(modes: CursorModes) {
        cursorModesBackCallback.isEnabled = modes.directNavigation || modes.grab
        //in the direct navigation mode a short B press of a game controller goes to the page
        host.backEvents.gameControllersLongPressBForBackNavigation = modes.directNavigation
    }

    override fun onUiEvent(event: BrowserUiEvent) {
        when (event) {
            is BrowserUiEvent.Progress -> showProgress(event.value)
            is BrowserUiEvent.TabUpdated -> vb.vTabs.onTabUpdated(event.tab)
            is BrowserUiEvent.ThumbnailUpdated -> {
                if (vb.rlActionBar.isVisible && event.tab == controller.currentTab.value) {
                    lifecycleScope.launch { displayThumbnail(event.tab) }
                }
            }
            is BrowserUiEvent.PopupBlocked -> {
                val msg = activity.getString(if (event.newTab) R.string.new_tab_blocked else R.string.popup_dialog_blocked)
                NotificationView.show(vb.rlRoot, R.drawable.ic_block_popups, msg)
            }
            BrowserUiEvent.TabOpenedByPage -> {
                if (vb.rlActionBar.visibility == View.VISIBLE) {
                    hideMenuOverlay()
                }
            }
            is BrowserUiEvent.HomePageSearchBarLocated -> {
                //only for a tab the user opened from the bar, so a page reload does not move the cursor
                if (event.tab == tabForSearchBarCursor) {
                    tabForSearchBarCursor = null
                    event.tab.webEngine.virtualCursor?.moveCursorTo(event.x.toFloat(), event.y.toFloat())
                }
            }
            BrowserUiEvent.TabClosedByPage -> hideMenuOverlay()
            else -> {}
        }
    }

    private fun showProgress(newProgress: Int) {
        vb.progressBar.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            vb.progressBar.setProgress(newProgress, true)
        } else {
            vb.progressBar.progress = newProgress
        }
        uiHandler.removeCallbacks(progressBarHideRunnable)
        if (newProgress == 100) {
            uiHandler.postDelayed(progressBarHideRunnable, 1000)
        } else {
            uiHandler.postDelayed(progressBarHideRunnable, 5000)
        }
    }

    private fun toggleAdBlockForTab() {
        val tab = controller.currentTab.value ?: return
        controller.setSiteAdBlock(tab, !controller.currentTabState.value.adBlockEnabled)
    }

    private fun showPopupBlockOptions() = lifecycleScope.launch {
        val tab = controller.currentTab.value ?: return@launch
        val currentHostConfig = controller.loadHostConfig(tab)
        val currentBlockPopupsLevelValue = currentHostConfig?.popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE
        val hostName = currentHostConfig?.hostName ?: try { URL(tab.url).host } catch (e: Exception) { "" }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.block_popups_s, hostName))
            .setSingleChoiceItems(R.array.popup_blocking_level, currentBlockPopupsLevelValue) {
                    dialog, itemId -> lifecycleScope.launch {
                        controller.changePopupBlockingLevel(itemId, tab)
                        dialog.dismiss()
                    }
            }
            .show()
    }

    private fun toggleMenu() {
        if (vb.rlActionBar.isInvisible) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    /** Lowest priority: Back on the remote opens and closes the bar */
    private val menuBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = toggleMenu()
    }

    /** Highest priority: leaves the direct navigation or the grab mode of the cursor */
    private val cursorModesBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val cursor = controller.currentTab.value?.webEngine?.virtualCursor ?: return
            if (!cursor.isVirtualCursorMode()) {
                cursor.setVirtualCursorMode(true)
            } else {
                cursor.getCursorDrawerDelegate()?.handleBackNavigation()
            }
        }
    }

    override fun registerBackCallbacks(dispatcher: OnBackPressedDispatcher, fullscreen: OnBackPressedCallback) {
        //the enabled callback added last goes first
        dispatcher.addCallback(activity, menuBackCallback)
        dispatcher.addCallback(activity, vb.vSitePanel.backCallback)
        dispatcher.addCallback(activity, vb.vTabMenu.backCallback)
        dispatcher.addCallback(activity, fullscreen)
        dispatcher.addCallback(activity, vb.vCursorMenu.backCallback)
        dispatcher.addCallback(activity, cursorModesBackCallback)
    }

    private fun showMenuOverlay() {
        vb.ivMiniatures.visibility = View.VISIBLE
        vb.flWebViewContainer.visibility = View.INVISIBLE
        val currentTab = controller.currentTab.value
        if (currentTab != null) {
            lifecycleScope.launch {
                currentTab.webEngine.renderThumbnail(currentTab.thumbnail)?.let { currentTab.updateThumbnail(it) }
                displayThumbnail(currentTab)
            }
        }

        vb.vActionBar.cancelEditing()

        vb.rlActionBar.visibility = View.VISIBLE
        vb.rlActionBar.translationY = -vb.rlActionBar.height.toFloat()
        vb.rlActionBar.alpha = 0f
        vb.rlActionBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    vb.vActionBar.catchFocus()
                }
                .start()

        vb.ivMiniatures.layoutParams = vb.ivMiniatures.layoutParams.apply { this.height = vb.flWebViewContainer.height }
        vb.ivMiniatures.translationY = 0f
        vb.ivMiniatures.animate()
                .translationY(vb.rlActionBar.height.toFloat())
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
    }

    private suspend fun displayThumbnail(currentTab: BrowserTab?) {
        if (currentTab != null) {
            if (tabByTitleIndex(vb.vTabs.current) != currentTab) return
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
            val thumbnail = controller.loadThumbnail(currentTab)
            if (thumbnail != null) {
                vb.ivMiniatures.setImageBitmap(thumbnail)
            } else {
                vb.ivMiniatures.setImageResource(0)
            }
        } else {
            vb.llMiniaturePlaceholder.visibility = View.VISIBLE
            vb.ivMiniatures.setImageResource(0)
            vb.ivMiniatures.visibility = View.INVISIBLE
        }
    }

    private fun hideMenuOverlay() {
        if (vb.rlActionBar.visibility == View.INVISIBLE) {
            return
        }
        vb.vSitePanel.hide()
        vb.vTabMenu.hide()
        tabOptionsHintAnchor = null
        vb.llTabOptionsHint.visibility = View.GONE

        vb.rlActionBar.animate()
                .translationY(-vb.rlActionBar.height.toFloat())
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    vb.rlActionBar.visibility = View.INVISIBLE
                }
                .start()

        if (vb.llMiniaturePlaceholder.visibility == View.VISIBLE) {
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
        }

        vb.ivMiniatures.translationY = vb.rlActionBar.height.toFloat()
        vb.ivMiniatures.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    vb.ivMiniatures.visibility = View.INVISIBLE
                    vb.rlActionBar.visibility = View.INVISIBLE
                    vb.ivMiniatures.setImageResource(0)
                    returnToCurrentTab()
                    vb.flWebViewContainer.visibility = View.VISIBLE
                    controller.currentTab.value?.webEngine?.getView()?.requestFocus()
                }
                .start()
    }

    /** Leaving the bar does not switch tabs: the previewed tab becomes the current one again */
    private fun returnToCurrentTab() {
        if (controller.tabsStates.isEmpty()) {
            host.openInNewTab(settingsModel.homePage, 0)
            return
        }
        vb.vTabs.resetPreview()
        vb.vActionBar.setAddressBoxText(controller.currentTabState.value.url)
    }

    /** OK on a tab switches to it */
    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vb.vTabs.current)
        if (tab == null) {
            host.openInNewTab(settingsModel.homePage, if (vb.vTabs.current < 0) 0 else controller.tabsStates.size)
        } else if (!tab.selected) {
            controller.changeTab(tab)
        }
    }
}
