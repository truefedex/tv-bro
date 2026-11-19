package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.utils.launchForResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.text.format.DateUtils
import android.widget.RelativeLayout
import android.view.KeyEvent
import android.os.SystemClock
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.browser.HomePageUrl
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.PictureInPictureActivity
import com.phlox.tvwebbrowser.activity.main.view.ActionBar
import com.phlox.tvwebbrowser.activity.main.view.BookmarksPanelView
import com.phlox.tvwebbrowser.activity.main.view.ClearDownloadsDialog
import com.phlox.tvwebbrowser.activity.main.view.PopupMenuView
import com.phlox.tvwebbrowser.activity.main.view.SiteSettingsView
import com.phlox.tvwebbrowser.activity.main.view.TabMenuView
import com.phlox.tvwebbrowser.activity.main.view.TvFindBarView
import com.phlox.tvwebbrowser.activity.main.view.TvSendScreenView
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.DownloadEvent
import com.phlox.tvwebbrowser.browser.DownloadsModel
import com.phlox.tvwebbrowser.browser.StreamDownloads
import com.phlox.tvwebbrowser.browser.search.SearchEngineSuggestions
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.send.CloudInbox
import com.phlox.tvwebbrowser.send.LocalSendPage
import com.phlox.tvwebbrowser.send.SendRules
import com.phlox.tvwebbrowser.send.SendSession
import com.phlox.tvwebbrowser.send.SendSettings
import com.phlox.tvwebbrowser.utils.VoiceSearchHelper
import com.phlox.tvwebbrowser.utils.BaseAnimationListener
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.webengine.CursorModes
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.webengine.virtualCursor
import com.phlox.tvwebbrowser.webengine.findInPage
import com.phlox.tvwebbrowser.webengine.mediaPlayback
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideosSupport
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.activity.main.view.PlayerScreenView
import com.phlox.tvwebbrowser.activity.main.view.PlayerPanelView
import com.phlox.tvwebbrowser.player.PictureInPicturePlayback
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.pageVideosSupport
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideoMatch
import com.phlox.tvwebbrowser.profile.ProfilePolicy
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.activity.main.view.VideosPanelView
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The TV look: the bar above the page (its row of buttons and the address, and the tabs; opened and
 * closed with Back), its panels and menus, and the virtual cursor.
 */
class TvShell(private val host: BrowserShell.Host) : BrowserShell, ActionBar.Callback {
    companion object {
        private val TAG = TvShell::class.java.simpleName
    }

    private val activity = host.activity
    /** Recognizes speech for the bookmarks search and Find in page (not for the search of the bar, which shows the page) */
    private val bookmarksVoiceSearch = VoiceSearchHelper(activity, ::showMessage)
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
        override fun run() = displayThumbnail(tabState)
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

    /** The Videos button of the bar */
    override fun showPageVideos() {
        vb.vVideosPanel.show(vb.vActionBar.videosButton, vb.vActionBar.functionsGroup)
    }

    private val videosCallback = object : VideosPanelView.Callback {
        //the video the page plays goes on from where it is
        override fun play(video: PageVideo) {
            val playing = isPageMedia(video)
            openBuiltInPlayer(video, if (playing) mediaPosition() else 0.0, pageMedia = playing)
        }

        override fun openInOtherApp(video: PageVideo) {
            this@TvShell.openInOtherApp(video)
        }

        override fun copyLink(video: PageVideo) = host.copyToClipboard(video.url)

        override fun download(video: PageVideo) = downloadVideo(video) { options ->
            vb.vVideosPanel.showDownloadChoices(video, options) { startStreamDownload(video, it) }
        }
    }

    /** Reading the playlists of a stream to download */
    private var streamLookup: Job? = null

    /**
     * Downloads a file as it is; for a stream, reads its qualities first: one is downloaded at once, more are
     * offered by [choose], a stream that can not be saved is explained in a toast
     */
    private fun downloadVideo(video: PageVideo, choose: (List<StreamDownloads.Option>) -> Unit) {
        val cookies = controller.currentTab.value?.webEngine?.getCookies(video.url)
        if (!StreamDownloads.isDownloadable(video)) {
            host.download(DownloadRequest.forLink(video.url, video.pageUrl ?: "", video.userAgent, cookies))
            return
        }
        if (streamLookup?.isActive == true) return
        streamLookup = lifecycleScope.launch {
            when (val result = StreamDownloads.options(video, cookies)) {
                is StreamDownloads.Result.Refused ->
                    showMessage(R.drawable.ic_downloads, activity.getString(R.string.stream_download_failed,
                        DownloadRowText(activity).reason(result.error)))
                is StreamDownloads.Result.Options ->
                    if (result.options.size == 1) startStreamDownload(video, result.options.single()) else choose(result.options)
            }
        }
    }

    private fun startStreamDownload(video: PageVideo, option: StreamDownloads.Option) {
        val cookies = controller.currentTab.value?.webEngine?.getCookies(video.url)
        val title = video.title?.takeIf { it.isNotBlank() } ?: controller.currentTab.value?.title
        host.download(StreamDownloads.request(video, option, title, cookies))
    }

    /** In a player app chosen in the dialog of Android; @return false if there is none (the user is told) */
    private fun openInOtherApp(video: PageVideo): Boolean {
        if (!policy.externalApps) return false
        val cookies = controller.currentTab.value?.webEngine?.getCookies(video.url)
        val opened = ExternalVideoPlayer.open(activity, video, cookies)
        if (!opened) NotificationView.show(vb.rlRoot, R.drawable.ic_video, activity.getString(R.string.err_no_video_player))
        return opened
    }

    /** The [video] is the one the media of the page plays (by address: the list may tell more about it than its frame) */
    private fun isPageMedia(video: PageVideo): Boolean =
        video.url == mediaVideo?.url || mediaVideoChoices.any { it.url == video.url }

    /** The engine of the shown tab, if it has one yet (back from the picture-in-picture window to a new activity) */
    private val currentEngine get() = controller.currentTab.value?.takeIf { it.isWebEngineCreated }?.webEngine

    /**
     * Plays the [video] in the built-in player from [startPosition] seconds: the bar closes and the page pauses its
     * media (the player plays it instead). A page in fullscreen stays so under the player: [pageMedia] (the video the
     * page plays) goes back to it from where the player is. [auto]: the rules opened it ([PlayerChoice]), and the
     * page gets the video back if it does not start. The first few times a toast tells what the player is for, once
     * the video shows ([PlayerScreenView.Callback.onFirstFrame]).
     */
    private fun openBuiltInPlayer(video: PageVideo, startPosition: Double, pageMedia: Boolean, auto: Boolean = false) {
        //one video at a time: it ends the picture-in-picture window
        PictureInPicturePlayback.end()
        coverPageWithPlayer()
        val state = controller.currentTabState.value
        vb.vPlayerScreen.show(video, startPosition, pageMedia, auto, title = state.title, site = state.host)
    }

    /** The bar closes and the page pauses its media before the player shows */
    private fun coverPageWithPlayer() {
        vb.vVideosPanel.hide()
        hideMenuOverlay()
        //the keys and the seek bar are the player's: before the pause, which would show the bar of the page
        videoMode.suspended = true
        playerChoice.playerOpen = true
        currentEngine?.mediaPlayback?.pausePlayback()
    }

    override fun showPictureInPicturePlayer(): Boolean {
        val player = PictureInPicturePlayback.takeBack() ?: return true
        coverPageWithPlayer()
        val video = player.state.value?.video
        val state = controller.currentTabState.value
        vb.vPlayerScreen.adopt(player, pageMedia = video != null && isPageMedia(video), title = state.title, site = state.host)
        return true
    }

    private val playerCallback = object : PlayerScreenView.Callback {
        override fun cookies(url: String): String? = controller.currentTab.value?.webEngine?.getCookies(url)

        override fun openInOtherApp(video: PageVideo): Boolean = this@TvShell.openInOtherApp(video)

        override fun download(video: PageVideo) = downloadVideo(video) { options ->
            vb.vPlayerScreen.showDownloadChoices(video, options) { startStreamDownload(video, it) }
        }

        override fun onClosed(exit: PlayerScreenView.Exit, position: Double?) {
            //the activity ends, or the player of the picture-in-picture window takes the place of this one
            if (exit == PlayerScreenView.Exit.CLOSED) return
            if (exit == PlayerScreenView.Exit.PAGE) {
                playerChoice.playerOpen = false
                videoMode.suspended = false
                return
            }
            val engine = currentEngine
            val media = engine?.mediaPlayback
            when (exit) {
                PlayerScreenView.Exit.SITE_PLAYER, PlayerScreenView.Exit.FAILED -> {
                    position?.let { media?.seekTo(it) }
                    media?.resumePlayback()
                    //the page keeps its videos: the rules do not take them again
                    playerChoice.keepPagePlayer()
                    if (exit == PlayerScreenView.Exit.FAILED) {
                        NotificationView.show(vb.rlRoot, R.drawable.ic_play_circle,
                            activity.getString(R.string.player_fallback), top = true)
                    }
                }
                PlayerScreenView.Exit.BACK -> position?.let { media?.seekTo(it) }
                else -> {}
            }
            playerChoice.playerOpen = false
            val leaveFullscreen = fullscreen && (exit == PlayerScreenView.Exit.BACK || exit == PlayerScreenView.Exit.ELSEWHERE)
            if (leaveFullscreen) {
                //the video mode of the page stays off until fullscreen is gone: no seek bar meanwhile
                engine?.hideFullscreenView()
            } else {
                videoMode.suspended = false
            }
            //in fullscreen the fullscreen view of the page kept the focus (the view of the engine is hidden behind it)
            if (!fullscreen) engine?.getView()?.requestFocus()
        }

        //the intro tells what the player gives: when it plays (not over the error of a video that does not start)
        override fun onFirstFrame() {
            val config = AppContext.provideConfig()
            if (config.builtInPlayerIntroShown >= Config.BUILT_IN_PLAYER_INTRO_TIMES) return
            config.builtInPlayerIntroShown++
            NotificationView.show(vb.rlRoot, R.drawable.ic_play_circle, activity.getString(R.string.player_opened), top = true)
        }

        override fun openPictureInPicture() = PictureInPictureActivity.start(activity)

        override fun sitePlayer(): PlayerScreenView.SitePlayer? {
            val host = controller.currentTabState.value.host.ifEmpty { return null }
            return PlayerScreenView.SitePlayer(host, controller.builtInPlayerSetting(host),
                AppContext.provideConfig().builtInPlayerByDefault)
        }

        override fun setSitePlayer(builtIn: Boolean?) {
            val host = controller.currentTabState.value.host.ifEmpty { return }
            controller.setBuiltInPlayer(host, builtIn)
            playerChoice.builtInPlayerPreferred = controller.prefersBuiltInPlayer(host)
        }
    }

    override fun onMediaCommand(command: MediaCommand): Boolean = vb.vPlayerScreen.mediaCommand(command)

    /** The Downloads button of the bar */
    override fun showDownloads() {
        if (!policy.downloads) return
        vb.vDownloadsPanel.show(vb.vActionBar.downloadsButton, vb.vActionBar.functionsGroup)
    }

    override fun showAllDownloads(focusId: Long?) {
        if (!policy.downloads) return
        vb.vDownloadsPanel.hide()
        vb.vDownloadsScreen.show(focusId)
    }

    private val downloadsModel = ActiveModelsRepository.get(DownloadsModel::class, activity)
    /** What the profile of this process refuses is not shown */
    private val policy = ProfilePolicy.current

    private val downloadsCallback = object : DownloadsNavigator.ShellCallback {
        override val downloads get() = downloadsModel.downloads
        override val downloadCommands get() = host.downloadCommands
        override val downloadDevice = DeviceDownloads(activity)

        override fun showMessage(icon: Int, message: String) {
            NotificationView.show(vb.rlRoot, icon, message)
        }

        override fun confirmClearAll(request: DownloadsNavigator.ClearAllRequest, onConfirm: (Boolean) -> Unit) =
            ClearDownloadsDialog.show(activity, request, onConfirm)
    }

    /** All downloads closed: the focus goes back to the bar, or to the page if the bar is closed */
    private fun onDownloadsScreenClosed() {
        if (vb.rlActionBar.isVisible) {
            vb.vActionBar.downloadsButton.requestFocus()
        } else {
            controller.currentTab.value?.webEngine?.getView()?.requestFocus()
        }
    }

    /** Toasts tell when downloads end; the panel is not opened by itself (it would take the remote from the page) */
    private fun onDownloadEvent(event: DownloadEvent) {
        when (event) {
            //over the player at the top: its seek bar is at the bottom, and its layer is above the toasts there
            is DownloadEvent.Completed -> NotificationView.show(vb.rlRoot, R.drawable.ic_downloads,
                activity.getString(R.string.download_complete_named, event.item.filename), top = vb.vPlayerScreen.isOpen)
            is DownloadEvent.Failed -> NotificationView.show(vb.rlRoot, R.drawable.ic_error,
                activity.getString(R.string.download_failed_named, event.item.filename), top = vb.vPlayerScreen.isOpen)
        }
    }

    private fun showHistory() {
        vb.vPopupMenu.hide()
        vb.vHistoryScreen.show()
    }

    private val historyCallback = object : HistoryNavigator.ShellCallback {
        override fun open(url: String) {
            hideMenuOverlay()
            host.navigate(url)
        }

        override fun openInNewTab(url: String) {
            host.openInNewTab(url, controller.tabsStates.size)
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
    }

    /** The history closed: the focus goes back to the ⋮ button of the bar, or to the page if the bar is closed */
    private fun onHistoryScreenClosed() {
        if (vb.rlActionBar.isVisible) {
            vb.vActionBar.moreButton.requestFocus()
        } else {
            controller.currentTab.value?.webEngine?.getView()?.requestFocus()
        }
    }

    /** Under the ⋮ button, its end under the end of the group of the button, as the panels of the bar */
    override fun showMoreMenu() {
        val items = arrayListOf(
            PopupMenuView.Item(R.drawable.ic_search, activity.getString(R.string.find_in_page)) { showFindInPage() },
            PopupMenuView.Item(R.drawable.ic_history, activity.getString(R.string.history)) { showHistory() }
        )
        if (policy.sendToTv) {
            items += PopupMenuView.Item(R.drawable.ic_devices, activity.getString(R.string.send_title)) { showSendScreen() }
        }
        if (policy.settings) {
            items += PopupMenuView.Item(R.drawable.ic_settings, activity.getString(R.string.settings)) { showSettings() }
        }
        //the other profile: the main one (behind the PIN) in the kids profile, the kids one in the main
        if (host.kidsProfileEnabled) {
            items += if (policy.kids) {
                PopupMenuView.Item(R.drawable.ic_lock, activity.getString(R.string.profile_main)) { switchProfile() }
            } else {
                PopupMenuView.Item(R.drawable.ic_child, activity.getString(R.string.profile_kids)) { switchProfile() }
            }
        }
        vb.vPopupMenu.show(vb.vActionBar.moreButton, items, alignEnd = true, endAlignTo = vb.vActionBar.functionsGroup,
            gap = activity.resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap),
            width = R.dimen.ds_size_more_menu_width, activateAnchor = true)
    }

    override fun showFavorites() {
        vb.vBookmarksPanel.show(vb.vActionBar.favoritesButton, vb.vActionBar.functionsGroup)
    }

    private val bookmarksCallback = object : BookmarksNavigator.ShellCallback {
        override fun currentPage() = BookmarksNavigator.PageInfo.of(controller.currentTab.value)

        override fun openBookmark(url: String) {
            hideMenuOverlay()
            host.navigate(url)
        }

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

    private fun switchProfile() {
        vb.vPopupMenu.hide()
        host.switchProfile()
    }

    override fun showSettings(category: SettingsNavigator.Category?) {
        if (!policy.settings) return
        vb.vPopupMenu.hide()
        vb.vSettingsScreen.show(category)
    }

    private val settingsCallback by lazy {
        SettingsShellCallback(host) { icon, message -> NotificationView.show(vb.rlRoot, icon, message) }
    }

    /** The settings closed: the focus goes back to the ⋮ button of the bar, or to the page if the bar is closed */
    private fun onSettingsClosed() {
        if (vb.rlActionBar.isVisible) {
            vb.vActionBar.moreButton.requestFocus()
        } else {
            controller.currentTab.value?.webEngine?.getView()?.requestFocus()
        }
    }

    /** A page shows a fullscreen view (a video): tabs sent by a phone wait behind it */
    private var fullscreen = false

    override fun onFullscreenChanged(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        if (fullscreen) findInPage.close()
        //the player that covered the page closed with Back or went elsewhere: the page left fullscreen
        if (!fullscreen && !vb.vPlayerScreen.isOpen) videoMode.suspended = false
        //the page left fullscreen by itself while the user chose its stream
        if (!fullscreen && vb.vVideoChoice.isOpen) {
            choiceResumes = false
            vb.vVideoChoice.close()
        }
        videoMode.setFullscreen(fullscreen)
        //the default may have changed in the settings meanwhile
        playerChoice.builtInPlayerPreferred = controller.prefersBuiltInPlayer(controller.currentTabState.value.host)
        playerChoice.setFullscreen(fullscreen)
        updatePageCovered()
    }

    /** The remote controls the video of the fullscreen view */
    private val videoMode = VideoModeController(object : VideoModeController.Host {
        private val engine get() = controller.currentTab.value?.webEngine

        override fun hideCursor() {
            engine?.virtualCursor?.getCursorDrawerDelegate()?.hideCursor()
        }

        override fun showCursor() {
            engine?.virtualCursor?.getCursorDrawerDelegate()?.animateAppearing()
        }

        override fun togglePlayback() = controller.togglePlayback()

        override fun seekTo(position: Double) {
            engine?.mediaPlayback?.seekTo(position)
        }

        override fun seekBy(delta: Double) {
            engine?.mediaPlayback?.seekBy(delta)
        }

        override fun renderOsd(osd: VideoModeController.Osd?) = vb.vVideoOsd.render(osd)

        override fun onActiveChanged(active: Boolean) = onCursorModesChanged(cursorModes)

        /**
         * Down takes the video of the page to the built-in player where its stream is known, else to the
         * Videos panel to pick it among the streams it may be ([PlayerChoice.down])
         */
        override fun onUpDown(down: Boolean): Boolean = down && downInVideoMode()
    }).apply { upHint = activity.getString(R.string.video_osd_cursor) }

    /**
     * Which player shows a video the page puts in fullscreen: the rules give it to the built-in player by themselves
     * (from the second the page is at) or leave it to the page
     */
    private val playerChoice = PlayerChoice(object : PlayerChoice.Host {
        override fun openBuiltInPlayer(video: PageVideo, position: Double) =
            this@TvShell.openBuiltInPlayer(video, position, pageMedia = true, auto = true)
    }, SystemClock::elapsedRealtime)

    /** What Down (and the Menu key) does in the video mode; false if nothing (it gives the cursor back) */
    private fun downInVideoMode(): Boolean = when (playerChoice.down) {
        PlayerChoice.Down.BUILT_IN_PLAYER -> openMediaVideoInPlayer()
        PlayerChoice.Down.CHOOSE_STREAM -> showMediaVideoChoices()
        PlayerChoice.Down.CURSOR -> false
    }

    /** The video of the page the media keys control plays, when its stream is known ([PageVideosSupport.mediaVideo]) */
    private var mediaVideo: PageVideo? = null
    /** The videos it may be when that is not clear, the likeliest first */
    private var mediaVideoChoices: List<PageVideo> = emptyList()
    /** All of it, with the choices of the same length */
    private var mediaVideoMatch = PageVideoMatch.NONE

    private fun onMediaVideoChanged(match: PageVideoMatch) {
        val video = match.video
        mediaVideo = video
        mediaVideoChoices = match.choices
        mediaVideoMatch = match
        playerChoice.setMatch(match)
        updateDownHint()
        pageVideoHint.setMediaVideo(video)
        updatePlayingVideo()
    }

    /** The seek bar of the video mode tells what Down does */
    private fun updateDownHint() {
        videoMode.hint = when (playerChoice.down) {
            PlayerChoice.Down.BUILT_IN_PLAYER -> activity.getString(R.string.built_in_player)
            //it goes there too, through the choice of the stream
            PlayerChoice.Down.CHOOSE_STREAM -> activity.getString(R.string.built_in_player)
            PlayerChoice.Down.CURSOR -> null
        }
    }

    /** The media of the shown page the keys control */
    private var media: MediaState? = null

    private fun onMediaStateChanged(media: MediaState?) {
        this.media = media
        videoMode.setMedia(media)
        playerChoice.setMedia(media)
        //a protected video takes Down away
        updateDownHint()
        pageVideoHint.setMedia(media)
        updatePlayingVideo()
    }

    /** The row of the Videos panel of the video that plays in the page says so */
    private fun updatePlayingVideo() {
        vb.vVideosPanel.setPlaying(mediaVideo?.takeIf { media?.playing == true }?.url)
    }

    /** The hint plate about a found video that plays in the page */
    private val pageVideoHint = PageVideoHintController(object : PageVideoHintController.Host {
        override val hintsAllowed: Boolean
            get() = AppContext.provideConfig().pageVideoHint

        override fun showHint() = vb.vPageVideoHint.appear()

        override fun hideHint(fade: Boolean) = vb.vPageVideoHint.disappear(fade)

        /** Back on the plate: the bar opens with the Videos panel, the focus on the video (Back, OK plays it) */
        override fun openVideos(video: PageVideo) {
            showMenuOverlay {
                vb.vVideosPanel.show(vb.vActionBar.videosButton, vb.vActionBar.functionsGroup, focusUrl = video.url)
            }
        }
    })

    /** Something covers the page (the hint waits): the bar, a panel, menu or screen, the player, fullscreen */
    private fun isPageCovered(): Boolean = fullscreen || vb.rlActionBar.isVisible ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            listOf(vb.vFindBar, vb.vSitePanel, vb.vTabMenu, vb.vPopupMenu, vb.vBookmarksPanel, vb.vDownloadsPanel, vb.vVideosPanel,
                vb.vDownloadsScreen, vb.vHistoryScreen, vb.vSendScreen, vb.vSettingsScreen, vb.vPlayerScreen, vb.vVideoChoice,
                vb.vCursorMenu, vb.progressBarGeneric).any { it.isVisible }

    private fun updatePageCovered() = pageVideoHint.setBlocked(isPageCovered())

    /** Overlays come and go with a redraw */
    private val pageCoveredDrawListener = ViewTreeObserver.OnPreDrawListener {
        updatePageCovered()
        true
    }

    /** The video of the fullscreen view goes on in the built-in player, from where it is; false if its stream is not known */
    private fun openMediaVideoInPlayer(): Boolean {
        val video = mediaVideo ?: return false
        openBuiltInPlayer(video, mediaPosition(), pageMedia = true)
        return true
    }

    /** Where the media of the page is now, seconds */
    private fun mediaPosition(): Double = controller.currentTab.value?.webEngine?.mediaPlayback?.mediaState?.value
        ?.positionAt(SystemClock.elapsedRealtime()) ?: 0.0

    /** The video of the page paused for the choice of a stream: it plays on when the user stays with the page */
    private var choiceResumes = false
    /** The view that had the focus before the choice of a stream took it (the fullscreen view of the page) */
    private var focusBeforeChoice: View? = null

    /**
     * The choice among the streams the video of the page may be, over the video: the page pauses (the second it
     * goes on from stays the same), OK goes on with the chosen stream in the built-in player from there, Back stays
     * with the player of the page; false if there are none
     */
    private fun showMediaVideoChoices(): Boolean {
        val choices = mediaVideoMatch.choices.takeIf { it.isNotEmpty() } ?: return false
        val position = mediaPosition()
        choiceResumes = media?.playing == true
        videoMode.suspended = true
        currentEngine?.mediaPlayback?.pausePlayback()
        focusBeforeChoice = activity.currentFocus
        val sameLength = mediaVideoMatch.sameLength
        val names = PageVideoRowText.fileNames(choices)
        val rows = choices.mapIndexed { i, video ->
            PlayerPanelView.Row(R.drawable.ic_play_circle, names[i],
                caption = PageVideoRowText.choiceCaption(video),
                badge = if (video.url in sameLength) activity.getString(R.string.choose_video_same_length) else null) {
                choiceResumes = false
                openBuiltInPlayer(video, position, pageMedia = true)
                vb.vVideoChoice.close()
            }
        }
        vb.vVideoChoice.open(PlayerPanelView.Page(activity.getString(R.string.videos_on_page),
            activity.getString(R.string.choose_video_caption, DateUtils.formatElapsedTime(position.toLong())), rows,
            activity.getString(R.string.choose_video_stay)))
        videoChoiceBackCallback.isEnabled = true
        return true
    }

    /** The choice of a stream closed: chosen (the built-in player took the video) or Back (the page plays it on) */
    private fun onVideoChoiceClosed() {
        videoChoiceBackCallback.isEnabled = false
        focusBeforeChoice?.takeIf { it.isAttachedToWindow && it.isShown }?.requestFocus()
        focusBeforeChoice = null
        //the built-in player keeps the video mode of the page off
        if (vb.vPlayerScreen.isOpen) return
        videoMode.suspended = false
        if (choiceResumes) currentEngine?.mediaPlayback?.resumePlayback()
        choiceResumes = false
    }

    /** Back on the choice of a stream: the page plays its video on */
    private val videoChoiceBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = vb.vVideoChoice.back()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vb.vPlayerScreen.dispatchPlayerKey(event)) return true
        //the Menu key (where it reaches the app) does what Down does in the video mode
        if (event.keyCode == KeyEvent.KEYCODE_MENU && videoMode.isActive && playerChoice.down != PlayerChoice.Down.CURSOR) {
            if (event.action == KeyEvent.ACTION_UP) downInVideoMode()
            return true
        }
        return videoMode.dispatchKeyEvent(event)
    }

    //the focus stays in the view of the page meanwhile (its fullscreen view): its cursor would take the D-pad of a pad
    override fun takesDpad(): Boolean = vb.vPlayerScreen.isOpen || vb.vVideoChoice.isOpen || videoMode.isActive

    /** Waits for what other devices send while the screen of sending is open */
    private val sendSettings = SendSettings(activity, AppContext.provideConfig().prefs)
    private val sendSession = SendSession(sendSettings, CloudInbox(), lifecycleScope, TVBro.container.appScope,
        object : SendSession.Host {
            private val page = LocalSendPage(activity, sendSettings.deviceName)

            override fun asset(path: String) = page.asset(path)

            override fun onText(text: String, from: String) = openSentText(text, from)

            override suspend fun receiveFile(name: String, mimeType: String?, size: Long, from: String) =
                sentFiles.receive(name, mimeType, size, from).also { addReceived(name) }
        })

    /** The files go to the downloads */
    private val sentFiles = SentFileReceiver(activity, host, { TVBro.container.data.downloads }) { message ->
        NotificationView.show(vb.rlRoot, R.drawable.ic_devices, message)
    }

    /** What came lately, the newest first, for the screen of sending */
    private val receivedItems = MutableStateFlow<List<String>>(emptyList())

    /** Texts that came before the tabs were loaded */
    private val pendingTexts = mutableListOf<Pair<String, String>>()

    private fun addReceived(item: String) {
        receivedItems.value = (listOf(item.take(200)) + receivedItems.value).take(8)
    }

    /**
     * A text another device sent opens as if typed into the address bar, in a new tab (or the tab of the page
     * if it is open already); the screens close. While a video is fullscreen or in the built-in player, the
     * tab waits at the end of the tabs.
     */
    private fun openSentText(text: String, from: String) {
        if (!controller.loaded) {
            pendingTexts += text to from
            return
        }
        val url = SendRules.urlOf(text, AppContext.provideConfig().searchEngineURL.value)
        if (url == null) {
            NotificationView.show(vb.rlRoot, R.drawable.ic_devices, activity.getString(R.string.send_cannot_open))
            return
        }
        addReceived(text)
        val open = controller.tabsStates.firstOrNull { it.url == url }
        if (!fullscreen && !vb.vPlayerScreen.isOpen) {
            vb.vSendScreen.hide()
            vb.vSettingsScreen.hide()
            vb.vDownloadsScreen.hide()
            vb.vHistoryScreen.hide()
            if (open != null) {
                controller.changeTab(open)
                showPage()
            } else {
                host.openInNewTab(url, controller.tabsStates.size)
            }
        } else if (open == null) {
            controller.addBackgroundTab(url, text, controller.tabsStates.size)
        }
        NotificationView.show(vb.rlRoot, R.drawable.ic_devices, if (from.isEmpty()) activity.getString(R.string.send_page_opened)
            else activity.getString(R.string.send_page_opened_from, from))
    }

    /** Android 17 asks the user before the app talks to devices of the local network */
    private fun showSendScreen() {
        if (!policy.sendToTv) return
        vb.vSendScreen.show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && ContextCompat.checkSelfPermission(activity,
                Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            activity.launchForResult(ActivityResultContracts.RequestPermission(), Manifest.permission.ACCESS_LOCAL_NETWORK) {}
        }
    }

    private val sendScreenCallback = object : TvSendScreenView.Callback {
        override val state get() = sendSession.state
        override val received get() = receivedItems

        override fun onOpened() = sendSession.open()

        override fun newLink() {
            AlertDialog.Builder(activity)
                .setMessage(R.string.send_new_link_confirm)
                .setPositiveButton(R.string.send_new_link) { _, _ -> sendSession.newLink() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        /** The focus goes back to the ⋮ button of the bar (its menu opened it), or to the page if the bar is closed */
        override fun onClosed() {
            sendSession.close()
            if (vb.rlActionBar.isVisible) {
                vb.vActionBar.moreButton.requestFocus()
            } else {
                controller.currentTab.value?.webEngine?.getView()?.requestFocus()
            }
        }
    }

    override fun showSitePanel() {
        //a page without a host has no site settings
        if (controller.currentTabState.value.host.isEmpty()) return
        vb.vSitePanel.show(vb.vActionBar.siteButton)
    }

    private val sitePanelCallback = object : SiteSettingsView.Callback {
        //the page reloads, the panel stays open with the focus on the row
        override fun toggleAdBlock() = host.toggleSiteAdBlock()

        override fun choosePopupBlockLevel() = host.choosePopupBlockLevel()

        override fun chooseSiteView() = host.chooseSiteView()

        override fun chooseSitePlayer() = this@TvShell.chooseSitePlayer()

        override fun clearSiteData() = host.clearSiteData()
    }

    /**
     * Player for videos in the site panel: the TV Bro player or the site's own for the videos the site puts in
     * fullscreen, chosen as the site view is (no reload: it applies to the next video in fullscreen, also on this page)
     */
    private fun chooseSitePlayer() {
        val state = controller.currentTabState.value
        val host = state.host.ifEmpty { return }
        val players = arrayOf(activity.getString(R.string.built_in_player), activity.getString(R.string.site_player))
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.video_player_s, host))
            .setSingleChoiceItems(players, if (state.builtInPlayer) 0 else 1) { dialog, position ->
                controller.setSitePlayer(host, builtIn = position == 0)
                playerChoice.builtInPlayerPreferred = controller.prefersBuiltInPlayer(host)
                playerChoice.siteChoiceChanged()
                dialog.dismiss()
            }
            .show()
    }

    override fun initiateVoiceSearch() = host.commands.startVoiceSearch()

    override fun search(text: String) = host.search(text)

    override fun openUrl(url: String) = host.navigate(url)

    /** The suggestions of the history and of the search engine under the address while it is edited */
    private val addressSuggestions = AddressSuggestions(TVBro.container.data.history, lifecycleScope,
        object : AddressSuggestions.Host {
            override fun render(rows: List<AddressSuggestions.Row>) {
                if (rows.isNotEmpty()) placeAddressSuggestions()
                vb.vAddressSuggestions.render(rows)
            }
        }, searchSource = SearchEngineSuggestions(AppContext.provideConfig()))

    /** The list of the suggestions goes under the address field, as wide as it */
    private fun placeAddressSuggestions() {
        val anchor = vb.vActionBar.addressField
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        vb.rlRoot.getLocationInWindow(rootLocation)
        val lp = vb.vAddressSuggestions.layoutParams as RelativeLayout.LayoutParams
        //the margins start inside the padding of the root (it fits the system windows)
        val left = anchorLocation[0] - rootLocation[0] - vb.rlRoot.paddingLeft
        val top = anchorLocation[1] - rootLocation[1] - vb.rlRoot.paddingTop + anchor.height +
            activity.resources.getDimensionPixelSize(R.dimen.ds_space_gutter)
        if (lp.leftMargin == left && lp.topMargin == top && lp.width == anchor.width) return
        lp.leftMargin = left
        lp.topMargin = top
        lp.width = anchor.width
        vb.vAddressSuggestions.layoutParams = lp
    }

    /** Back while a suggestion is chosen goes back to the text */
    private val addressSuggestionsBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = vb.vAddressSuggestions.clearHighlight()
    }

    override fun toggleIncognitoMode() = host.toggleIncognitoMode()

    /** Find in page: the find bar over the top end of the page */
    private val findInPage: FindInPageController = FindInPageController(lifecycleScope, object : FindInPageController.Host {
        override fun currentEngine() = controller.currentTab.value?.webEngine?.findInPage

        override fun render(state: FindInPageController.State?) {
            val wasOpen = vb.vFindBar.isVisible
            vb.vFindBar.render(state)
            findBackCallback.isEnabled = state != null
            //closed: the page has the focus again
            if (state == null && wasOpen && !vb.rlActionBar.isVisible) focusPage()
        }
    })

    private val findBarCallback = object : TvFindBarView.Callback {
        override fun onQueryChanged(text: String) = findInPage.setQuery(text)

        override fun next() = findInPage.next()

        override fun previous() = findInPage.previous()

        override fun close() = findInPage.close()

        override fun voiceSearch() = bookmarksVoiceSearch.recognize { vb.vFindBar.setQuery(it) }

        override fun focusPage() = this@TvShell.focusPage()
    }

    /** Back on the find bar closes it; Back in the page under it gives the focus back to the bar */
    private val findBackCallback: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (vb.vFindBar.hasFocus()) {
                findInPage.close()
            } else {
                vb.vFindBar.focusBar(findInPage.state?.hasMatches == true)
            }
        }
    }

    private fun focusPage() {
        controller.currentTab.value?.webEngine?.getView()?.requestFocus()
    }

    override fun showFindInPage() {
        //not over the screens, the player or a fullscreen video
        if (fullscreen || listOf(vb.vDownloadsScreen, vb.vHistoryScreen, vb.vSendScreen, vb.vSettingsScreen,
                vb.vPlayerScreen).any { it.isVisible }) return
        if (!findInPage.open()) return
        //the bar closes first: the field gets the focus when the page is shown
        if (vb.rlActionBar.isVisible) hideMenuOverlay() else vb.vFindBar.focusField()
    }

    //after the listeners: properties are initialized in their order
    init {
        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.rlActionBar.visibility = View.INVISIBLE
        vb.progressBar.visibility = View.GONE

        vb.vTabs.listener = tabsListener

        vb.vActionBar.callback = this
        vb.vActionBar.setSuggestions(addressSuggestions, vb.vAddressSuggestions)
        vb.vAddressSuggestions.onHighlightChanged = { addressSuggestionsBackCallback.isEnabled = it >= 0 }
        vb.vFindBar.callback = findBarCallback
        vb.vSitePanel.callback = sitePanelCallback
        //each step tells the zoom it gave the page
        vb.vCursorMenu.onZoom = { tab, zoomIn ->
            val onZoom = { zoom: Int ->
                showMessage(if (zoomIn) R.drawable.ic_zoom_in else R.drawable.ic_zoom_out,
                    activity.getString(R.string.zoom_percent, zoom))
            }
            if (zoomIn) controller.zoomIn(tab, onZoom) else controller.zoomOut(tab, onZoom)
        }
        vb.vTabMenu.callback = tabMenuCallback
        vb.vBookmarksPanel.attach(lifecycleScope, vb.vPopupMenu, bookmarksCallback)
        vb.vDownloadsPanel.attach(lifecycleScope, vb.vPopupMenu, downloadsCallback) { showAllDownloads(it) }
        vb.vVideosPanel.attach(vb.vPopupMenu, videosCallback)
        vb.vPlayerScreen.attach(lifecycleScope, playerCallback)
        vb.vVideoChoice.onClosed = ::onVideoChoiceClosed
        vb.vDownloadsScreen.attach(lifecycleScope, vb.vPopupMenu, downloadsCallback, ::onDownloadsScreenClosed)
        vb.vHistoryScreen.attach(lifecycleScope, vb.vPopupMenu, historyCallback, ::onHistoryScreenClosed)
        vb.vSendScreen.attach(lifecycleScope, sendScreenCallback)
        vb.vSettingsScreen.attach(lifecycleScope, vb.vPopupMenu, settingsCallback, ::onSettingsClosed)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            //the screen stayed open while the activity was stopped
            override fun onStart(owner: LifecycleOwner) {
                if (vb.vSendScreen.isOpen) sendSession.open()
            }
            override fun onPause(owner: LifecycleOwner) {
                vb.vPlayerScreen.onPause()
                pageVideoHint.setBlocked(true)
            }
            override fun onResume(owner: LifecycleOwner) {
                vb.vPlayerScreen.onResume()
                updatePageCovered()
            }
            override fun onStop(owner: LifecycleOwner) = sendSession.close()
            override fun onDestroy(owner: LifecycleOwner) {
                sendSession.close(abort = true)
                vb.vPlayerScreen.close()
            }
        })
        lifecycleScope.launch { downloadsModel.events.collect(::onDownloadEvent) }
        vb.rlRoot.viewTreeObserver.addOnGlobalFocusChangeListener(tabOptionsHintFocusListener)
        vb.rlRoot.viewTreeObserver.addOnPreDrawListener(tabOptionsHintDrawListener)
        vb.rlRoot.viewTreeObserver.addOnPreDrawListener(pageCoveredDrawListener)

        //TV screens do not rotate, but large screens may ignore the requested orientation:
        //the picture of a tab under the open menu has the height of the screen it was shown on
        vb.flWebViewContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && vb.rlActionBar.isVisible) {
                vb.root.post { fitPageUnderMenu() }
            }
        }
    }

    /** The page (or the picture of a previewed tab) is under the open bar */
    private fun fitPageUnderMenu() {
        if (!vb.rlActionBar.isVisible || menuOverlayCapture != null || menuOverlayClosing) return
        val barHeight = vb.rlActionBar.height.toFloat()
        vb.flWebViewContainer.animate().cancel()
        vb.flWebViewContainer.translationY = barHeight
        vb.ivMiniatures.layoutParams = vb.ivMiniatures.layoutParams.apply { height = vb.flWebViewContainer.height }
        vb.ivMiniatures.translationY = barHeight
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
        //a restored tab has the url the alias loaded
        if (currentTab == null || HomePageUrl.isHomePage(currentTab.url, AppContext.provideConfig())) {
            showMenuOverlay()
        }
        val texts = pendingTexts.toList()
        pendingTexts.clear()
        texts.forEach { (text, from) -> openSentText(text, from) }
    }

    override fun onNoBackHistory() {
        //no history: back to the menu
        if (vb.rlActionBar.visibility != View.VISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    override fun showMessage(icon: Int, message: String) {
        NotificationView.show(vb.rlRoot, icon, message)
    }

    override fun showPage() {
        //the player and the choice of a stream cover it too; the page they covered keeps its video paused
        vb.vPlayerScreen.close(PlayerScreenView.Exit.PAGE)
        if (vb.vVideoChoice.isOpen) {
            choiceResumes = false
            vb.vVideoChoice.close()
        }
        if (vb.rlActionBar.visibility == View.VISIBLE) {
            hideMenuOverlay()
        }
    }

    override fun onDownloadStarted(download: Download) {
        NotificationView.show(vb.rlRoot, R.drawable.ic_downloads,
            activity.getString(R.string.downloading_named_file, download.filename), top = vb.vPlayerScreen.isOpen)
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
        findInPage.onPage(state.tabIdentity, state.url, state.loading)
        vb.vActionBar.setNavigationState(state.canGoBack, state.canGoForward, state.loading)

        vb.vActionBar.setSiteState(state.host, state.connection)
        vb.vSitePanel.render(state)

        val tabChanged = state.tabIdentity != renderedTabIdentity
        renderedTabIdentity = state.tabIdentity
        //once per page: the tab and its address without the fragment
        val page = if (state.hasTab && state.url.isNotEmpty()) "${state.tabIdentity}|${state.url.substringBefore('#')}" else null
        pageVideoHint.setPage(page)
        playerChoice.builtInPlayerPreferred = controller.prefersBuiltInPlayer(state.host)
        //the seek bar of the video mode: the title of the tab, the player of the site
        vb.vVideoOsd.setInfo(state.title, PageVideoRowText.sitePlayerCaption(activity.getString(R.string.site_player), state.host))
        playerChoice.setPage(page)
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
    private var mediaStateJob: Job? = null
    private var pageVideosJob: Job? = null
    private var mediaVideoJob: Job? = null
    private var cursorModes = CursorModes()

    /**
     * Follows the cursor modes of the shown tab. The grab and direct navigation modes belong
     * to the page they were turned on for, so they end when another tab is shown.
     */
    private fun onRenderedTabChanged() {
        mediaStateJob?.cancel()
        mediaStateJob = null
        onMediaStateChanged(null)
        controller.currentTab.value?.webEngine?.mediaPlayback?.let { media ->
            mediaStateJob = lifecycleScope.launch { media.mediaState.collect { onMediaStateChanged(it) } }
        }
        pageVideosJob?.cancel()
        pageVideosJob = null
        mediaVideoJob?.cancel()
        mediaVideoJob = null
        onPageVideosChanged(emptyList())
        onMediaVideoChanged(PageVideoMatch.NONE)
        controller.currentTab.value?.webEngine?.pageVideosSupport?.let { videos ->
            pageVideosJob = lifecycleScope.launch { videos.pageVideos.collect { onPageVideosChanged(it) } }
            mediaVideoJob = lifecycleScope.launch { videos.mediaVideo.collect { onMediaVideoChanged(it) } }
        }
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

    /** The videos of the shown page other apps can play: the badge of the bar and the panel */
    private fun onPageVideosChanged(videos: List<PageVideo>) {
        vb.vActionBar.setPageVideoCount(videos.size)
        vb.vVideosPanel.render(videos)
    }

    private fun onCursorModesChanged(modes: CursorModes) {
        cursorModes = modes
        //in the video mode Back leaves fullscreen, whatever the mode of the hidden cursor
        cursorModesBackCallback.isEnabled = (modes.directNavigation || modes.grab) && !videoMode.isActive
        //in the direct navigation mode a short B press of a game controller goes to the page
        host.backEvents.gameControllersLongPressBForBackNavigation = modes.directNavigation && !videoMode.isActive
    }

    override fun onUiEvent(event: BrowserUiEvent) {
        when (event) {
            is BrowserUiEvent.Progress -> showProgress(event.value)
            is BrowserUiEvent.TabUpdated -> vb.vTabs.onTabUpdated(event.tab)
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
        dispatcher.addCallback(activity, addressSuggestionsBackCallback)
        //Back on the hint plate opens the bar at the Videos panel; it shows only while nothing else is open
        dispatcher.addCallback(activity, pageVideoHint.backCallback)
        dispatcher.addCallback(activity, findBackCallback)
        dispatcher.addCallback(activity, vb.vSitePanel.backCallback)
        dispatcher.addCallback(activity, vb.vTabMenu.backCallback)
        dispatcher.addCallback(activity, vb.vBookmarksPanel.backCallback)
        dispatcher.addCallback(activity, vb.vDownloadsScreen.backCallback)
        dispatcher.addCallback(activity, vb.vHistoryScreen.backCallback)
        dispatcher.addCallback(activity, vb.vSendScreen.backCallback)
        dispatcher.addCallback(activity, vb.vSettingsScreen.backCallback)
        dispatcher.addCallback(activity, vb.vDownloadsPanel.backCallback)
        dispatcher.addCallback(activity, vb.vVideosPanel.backCallback)
        dispatcher.addCallback(activity, vb.vPopupMenu.backCallback)
        dispatcher.addCallback(activity, fullscreen)
        dispatcher.addCallback(activity, videoMode.backCallback)
        dispatcher.addCallback(activity, vb.vCursorMenu.backCallback)
        dispatcher.addCallback(activity, cursorModesBackCallback)
        //the choice of a stream over the fullscreen video: Back stays with the player of the page, whatever the mode of
        //the cursor (the video mode is off meanwhile, so the callback of the cursor modes may be on)
        dispatcher.addCallback(activity, videoChoiceBackCallback)
        //the built-in player over everything: Back closes it, whatever the cursor of the page under it does
        dispatcher.addCallback(activity, vb.vPlayerScreen.backCallback)
        //the voice input of the bookmarks search: Back cancels it
        dispatcher.addCallback(activity, bookmarksVoiceSearch.backCallback)
    }

    /**
     * Opens the bar; [onShown] is called when it is in place (a panel opened under its button measures it there),
     * instead of giving the focus to the address
     */
    private fun showMenuOverlay(onShown: (() -> Unit)? = null) {
        //the bar covers the page: its search ends
        findInPage.close()
        vb.vActionBar.cancelEditing()
        menuOverlayCapture?.cancel()
        menuOverlayCapture = null
        menuOverlayClosing = false
        //a bar that was sliding away does not end hidden
        vb.flWebViewContainer.animate().cancel()

        //the bar is open at once (its back callback, the panels), but shows only once the picture of the page is
        //taken (for the previews of the tabs): it may be taken from the screen, where nothing may cover the page
        vb.rlActionBar.animate().cancel()
        vb.rlActionBar.visibility = View.VISIBLE
        vb.rlActionBar.translationY = -vb.rlActionBar.height.toFloat()
        vb.rlActionBar.alpha = 0f
        coverPage(true)

        val currentTab = controller.currentTab.value
        if (currentTab == null) {
            revealMenuOverlay(onShown)
            return
        }
        val pageShown = vb.flWebViewContainer.isVisible
        menuOverlayCapture = lifecycleScope.launch {
            if (currentTab.isWebEngineCreated) {
                currentTab.webEngine.renderThumbnail(currentTab.thumbnail, onScreen = pageShown)
                    ?.let { currentTab.updateThumbnail(it) }
            }
            menuOverlayCapture = null
            revealMenuOverlay(onShown)
        }
    }

    /** Taking the picture of the page for the bar that opens, see [showMenuOverlay] */
    private var menuOverlayCapture: Job? = null

    /** The bar slides away */
    private var menuOverlayClosing = false

    /**
     * While the bar is open the page stays live under it (its loads, Back and Forward of the bar show at once),
     * but takes no focus and shows no cursor
     */
    private fun coverPage(covered: Boolean) {
        vb.flWebViewContainer.descendantFocusability =
            if (covered) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_BEFORE_DESCENDANTS
        if (covered) vb.flWebViewContainer.findFocus()?.clearFocus()
        for (tab in controller.tabsStates) {
            if (tab.isWebEngineCreated) tab.webEngine.virtualCursor?.setCursorHidden(covered && tab.selected)
        }
    }

    private fun revealMenuOverlay(onShown: (() -> Unit)?) {
        showPreview(tabByTitleIndex(vb.vTabs.current))
        val barHeight = vb.rlActionBar.height.toFloat()

        vb.rlActionBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (onShown != null) onShown() else vb.vActionBar.catchFocus()
                }
                .start()

        //the page slides down under the bar, the picture of a previewed tab with it
        vb.ivMiniatures.layoutParams = vb.ivMiniatures.layoutParams.apply { this.height = vb.flWebViewContainer.height }
        for (view in listOf(vb.flWebViewContainer, vb.ivMiniatures)) {
            view.animate().cancel()
            view.translationY = 0f
            view.animate()
                    .translationY(barHeight)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
        }
    }

    /** The tab strip of the open bar went to [tab], see [showPreview] */
    private fun displayThumbnail(tab: BrowserTab?) {
        if (!vb.rlActionBar.isVisible || menuOverlayClosing || menuOverlayCapture != null) return
        if (tabByTitleIndex(vb.vTabs.current) != tab) return
        showPreview(tab)
    }

    /**
     * Shows the tab the tab strip of the open bar is at under it: the live page of the current tab, the picture of
     * another one (its engine is not switched), the placeholder for a new tab
     */
    private fun showPreview(tab: BrowserTab?) {
        when {
            tab == null -> {
                vb.llMiniaturePlaceholder.visibility = View.VISIBLE
                vb.ivMiniatures.setImageResource(0)
                vb.ivMiniatures.visibility = View.INVISIBLE
                vb.flWebViewContainer.visibility = View.INVISIBLE
            }
            tab == controller.currentTab.value -> {
                vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
                vb.ivMiniatures.setImageResource(0)
                vb.ivMiniatures.visibility = View.INVISIBLE
                vb.flWebViewContainer.visibility = View.VISIBLE
            }
            else -> {
                vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
                vb.flWebViewContainer.visibility = View.INVISIBLE
                vb.ivMiniatures.visibility = View.VISIBLE
                vb.ivMiniatures.setImageResource(0)
                lifecycleScope.launch {
                    val thumbnail = controller.loadThumbnail(tab)
                    if (tabByTitleIndex(vb.vTabs.current) == tab && vb.ivMiniatures.isVisible) {
                        vb.ivMiniatures.setImageBitmap(thumbnail)
                    }
                }
            }
        }
    }

    private fun hideMenuOverlay() {
        if (vb.rlActionBar.visibility == View.INVISIBLE || menuOverlayClosing) {
            return
        }
        //closed before it showed: it does not show
        menuOverlayCapture?.cancel()
        menuOverlayCapture = null
        menuOverlayClosing = true
        uiHandler.removeCallbacks(displayThumbnailRunnable)
        //the suggestions go with the editing at once, not when the bar is gone
        vb.vActionBar.cancelEditing()
        vb.vSitePanel.hide()
        vb.vTabMenu.hide()
        vb.vPopupMenu.hide()
        vb.vBookmarksPanel.hide()
        vb.vDownloadsPanel.hide()
        vb.vVideosPanel.hide()
        tabOptionsHintAnchor = null
        vb.llTabOptionsHint.visibility = View.GONE

        vb.rlActionBar.animate()
                .translationY(-vb.rlActionBar.height.toFloat())
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()

        //the page of the current tab comes back from under the bar, also when another tab was previewed
        vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
        vb.ivMiniatures.animate().cancel()
        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.ivMiniatures.setImageResource(0)
        vb.flWebViewContainer.visibility = View.VISIBLE
        vb.flWebViewContainer.animate().cancel()
        vb.flWebViewContainer.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    menuOverlayClosing = false
                    vb.rlActionBar.visibility = View.INVISIBLE
                    returnToCurrentTab()
                    coverPage(false)
                    if (findInPage.isOpen) vb.vFindBar.focusField() else focusPage()
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
