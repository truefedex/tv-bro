package com.phlox.tvwebbrowser.activity.main

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
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
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.childs
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
 * The TV look: the action bar and the tabs above the page (the "menu overlay", opened and closed
 * with Back), the bottom panel and the virtual cursor.
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
            host.openInNewTab(settingsModel.homePage, controller.tabsStates.size)
        }

        override fun closeTab(tabState: BrowserTab?) = this@TvShell.closeTab(tabState)

        override fun openInNewTab(url: String, tabIndex: Int) {
            //the menu stays open
            controller.openInNewTab(url, tabIndex, navigateImmediately = true)
        }
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

    override fun initiateVoiceSearch() = host.commands.startVoiceSearch()

    override fun search(text: String) = host.search(text)

    override fun toggleIncognitoMode() = host.toggleIncognitoMode()

    private val bottomButtonsOnTouchListener = View.OnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                return@OnTouchListener true
            }
            MotionEvent.ACTION_UP -> {
                hideMenuOverlay(false)
                v.performClick()
                return@OnTouchListener true
            }
            else -> return@OnTouchListener false
        }
    }

    private val bottomButtonsFocusListener = View.OnFocusChangeListener { view, hasFocus ->
        if (hasFocus) {
            hideMenuOverlay(false)
        }
    }

    private val bottomButtonsKeyListener = View.OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    hideBottomPanel()
                    controller.currentTab.value?.webEngine?.getView()?.requestFocus()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    //after the listeners: properties are initialized in their order
    init {
        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.llBottomPanel.visibility = View.INVISIBLE
        vb.rlActionBar.visibility = View.INVISIBLE
        vb.progressBar.visibility = View.GONE

        vb.vTabs.listener = tabsListener

        vb.ibAdBlock.setOnClickListener { toggleAdBlockForTab() }
        vb.ibPopupBlock.setOnClickListener { showPopupBlockOptions() }
        vb.ibHome.setOnClickListener { host.commands.goHome() }
        vb.ibCloseTab.setOnClickListener { controller.currentTab.value?.apply { closeTab(this) } }

        vb.vActionBar.callback = this

        vb.llBottomPanel.childs.forEach {
            it.setOnTouchListener(bottomButtonsOnTouchListener)
            it.onFocusChangeListener = bottomButtonsFocusListener
            it.setOnKeyListener(bottomButtonsKeyListener)
        }

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

    override fun onExtendedAddressBarMode() {
        vb.llBottomPanel.visibility = View.INVISIBLE
    }

    override fun onUrlInputDone() {
        hideMenuOverlay()
    }

    private fun closeTab(tab: BrowserTab?) {
        if (tab == null) return
        controller.closeTab(tab)
        hideMenuOverlay(true)
        hideBottomPanel()
    }

    override fun showBusy(busy: Boolean) {
        vb.progressBarGeneric.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            vb.progressBarGeneric.requestFocus()
        }
    }

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
            hideMenuOverlay(true)
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

        vb.ibAdBlock.setImageResource(if (state.adBlockEnabled) R.drawable.ic_adblock_on else R.drawable.ic_adblock_off)
        vb.tvBlockedAdCounter.visibility = if (state.adBlockEnabled && state.blockedAds != 0) View.VISIBLE else View.GONE
        vb.tvBlockedAdCounter.text = state.blockedAds.toString()

        vb.tvBlockedPopupCounter.visibility = if (state.blockedPopups != 0) View.VISIBLE else View.GONE
        vb.tvBlockedPopupCounter.text = state.blockedPopups.toString()

        vb.vActionBar.setAddressBoxTextColor(if (state.certificateError) Color.RED else
            activity.themeColor(R.attr.tbColorOnSurface))

        val tabChanged = state.tabIdentity != renderedTabIdentity
        renderedTabIdentity = state.tabIdentity
        if (tabChanged) {
            onRenderedTabChanged()
        }
        //the address is not touched while the user edits it or browses the tabs list
        //(then the address of the focused tab is shown)
        if (tabChanged || (!vb.vActionBar.isAddressBoxFocused() &&
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
                NotificationView.showBottomRight(vb.rlRoot, R.drawable.ic_block_popups, msg)
            }
            BrowserUiEvent.TabOpenedByPage -> {
                if (vb.rlActionBar.visibility == View.VISIBLE) {
                    hideMenuOverlay(true)
                }
            }
            BrowserUiEvent.TabClosedByPage -> {
                hideMenuOverlay(true)
                hideBottomPanel()
            }
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

    /** Lowest priority: the menu overlay, the full menu or only its bottom panel */
    private val menuBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (vb.llBottomPanel.isVisible && !vb.rlActionBar.isVisible) {
                hideBottomPanel()
            } else {
                toggleMenu()
            }
        }
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
        dispatcher.addCallback(activity, fullscreen)
        dispatcher.addCallback(activity, vb.vCursorMenu.backCallback)
        dispatcher.addCallback(activity, cursorModesBackCallback)
    }

    private fun showMenuOverlay() {
        vb.ivMiniatures.visibility = View.VISIBLE
        vb.llBottomPanel.visibility = View.VISIBLE
        vb.flWebViewContainer.visibility = View.INVISIBLE
        val currentTab = controller.currentTab.value
        if (currentTab != null) {
            lifecycleScope.launch {
                currentTab.webEngine.renderThumbnail(currentTab.thumbnail)?.let { currentTab.updateThumbnail(it) }
                displayThumbnail(currentTab)
            }
        }

        vb.llBottomPanel.translationY = vb.llBottomPanel.height.toFloat()
        vb.llBottomPanel.alpha = 0f
        vb.llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .translationY(0f)
                .alpha(1f)
                .withEndAction {
                    vb.vActionBar.catchFocus()
                }
                .start()

        vb.vActionBar.dismissExtendedAddressBarMode()

        vb.rlActionBar.visibility = View.VISIBLE
        vb.rlActionBar.translationY = -vb.rlActionBar.height.toFloat()
        vb.rlActionBar.alpha = 0f
        vb.rlActionBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
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

    private fun hideMenuOverlay(hideBottomButtons: Boolean = true) {
        if (vb.rlActionBar.visibility == View.INVISIBLE) {
            return
        }
        if (hideBottomButtons) {
            hideBottomPanel()
        }

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
                    syncTabWithTitles()
                    vb.flWebViewContainer.visibility = View.VISIBLE
                    if (hideBottomButtons) {
                        controller.currentTab.value?.webEngine?.getView()?.requestFocus()
                    }
                }
                .start()
    }

    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vb.vTabs.current)
        if (tab == null) {
            host.openInNewTab(settingsModel.homePage, if (vb.vTabs.current < 0) 0 else controller.tabsStates.size)
        } else if (!tab.selected) {
            controller.changeTab(tab)
        }
    }

    private fun hideBottomPanel() {
        if (vb.llBottomPanel.visibility != View.VISIBLE) return
        vb.llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .translationY(vb.llBottomPanel.height.toFloat())
                .withEndAction {
                    vb.llBottomPanel.translationY = 0f
                    vb.llBottomPanel.visibility = View.INVISIBLE
                }
                .start()
    }
}
