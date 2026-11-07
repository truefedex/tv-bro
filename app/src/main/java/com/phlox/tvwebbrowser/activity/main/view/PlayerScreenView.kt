package com.phlox.tvwebbrowser.activity.main.view

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.PageVideoRowText
import com.phlox.tvwebbrowser.activity.main.VideoModeController
import com.phlox.tvwebbrowser.databinding.ViewPlayerScreenBinding
import com.phlox.tvwebbrowser.player.FrameRateSwitcher
import com.phlox.tvwebbrowser.player.NativePlayer
import com.phlox.tvwebbrowser.player.PictureInPicturePlayback
import com.phlox.tvwebbrowser.player.PictureInPictureSupport
import com.phlox.tvwebbrowser.player.PlaybackFailure
import com.phlox.tvwebbrowser.player.PlayerStart
import com.phlox.tvwebbrowser.player.PlayerTracks
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.PageVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The built-in player on TV (TV Bro player), over the whole window ([NativePlayer]), in the dark tokens of
 * ThemeOverlay.TVBro.Video. The keys are those of the video mode ([VideoModeController]: Left and Right seek, faster
 * while held, OK plays and pauses); the seek bar ([VideoOsdView]: the title of the tab, the caption of the player, the
 * height of the video, the kind and the site) shows on seeks, on play and pause and while paused. Down and the Menu
 * key (where it reaches the app) open the video settings ([PlayerPanelView] at the end of the screen): the quality,
 * the audio track, the subtitles, the speed ([PlayerTracks]) and the repeat (not for live streams, off for each
 * video), each with its options, picture-in-picture, the player of the fullscreen videos of the site of the page
 * ([SitePlayer]: the default, this one or the site's, which takes the video back to the page) and another app.
 * For the video of the page ([show] with pageMedia) Up gives it back to the page from where the player is
 * ("↑ Site player"); the page stays in fullscreen under the player meanwhile. Back closes the panel (a page of it at a
 * time), then the player ([Callback.onClosed] tells the page how, [Exit]). The player takes the focus only for the
 * panel and the error, and gives it back to the view that had it (the fullscreen view of the page). Until the first
 * frame of the video the screen is transparent: the paused page shows through, dimmed, under the spinner. A video
 * that the rules opened by themselves ([show] with auto, [com.phlox.tvwebbrowser.activity.main.PlayerChoice]) goes
 * back to the page when it does not start ([PlayerStart], [Exit.FAILED]); for others, a failed playback shows the
 * error card ([PlaybackFailure]), which offers the player of the site (the way back to the page for another video)
 * and another app. Where the device has picture-in-picture ([PictureInPictureSupport]), the panel and the PiP key of
 * remotes hand the playing video to a picture-in-picture window over the browser ([enterPictureInPicture],
 * [PictureInPicturePlayback]), and it comes back here when the window is expanded ([adopt]).
 */
class PlayerScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    interface Callback {
        /** The cookies of the engine for the address */
        fun cookies(url: String): String?
        /** Opens the video in another app; false if there is none */
        fun openInOtherApp(video: PageVideo): Boolean
        /** Downloads the video, a file ([PageVideo.Kind.FILE]: streams are no files to download) */
        fun download(video: PageVideo)
        /**
         * The player closed ([exit]); [position] is where it was, in seconds, when it played the video of the page
         * (null for another video)
         */
        fun onClosed(exit: Exit, position: Double?)
        /** The first frame of a video [show] opened is on the screen (not of one that fails before it) */
        fun onFirstFrame()
        /** Starts the picture-in-picture window of the player handed to [PictureInPicturePlayback] */
        fun openPictureInPicture()
        /** Which player the site of the page chose for its fullscreen videos; null for a page without a site */
        fun sitePlayer(): SitePlayer?
        /** The site of the page chooses [builtIn] (true: this player, false: its own, null: the default) */
        fun setSitePlayer(builtIn: Boolean?)
    }

    /**
     * The player of the fullscreen videos of the site ([host]): [setting] true the built-in one, false its own, null
     * the default ([byDefault]: true the built-in one)
     */
    data class SitePlayer(val host: String, val setting: Boolean?, val byDefault: Boolean) {
        /** The player its videos get */
        val builtIn: Boolean get() = setting ?: byDefault
    }

    /** How the player closed: what the page does with its video then */
    enum class Exit {
        /** Back (and Back to the page of the error): the page leaves fullscreen, its video waits where the player was */
        BACK,
        /** Up (and Watch in the site player of the error): the page plays its video on from where the player was */
        SITE_PLAYER,
        /** The video went on in the picture-in-picture window or in another app: the page leaves fullscreen */
        ELSEWHERE,
        /** The rules opened the player and it did not start: the page plays its video on, as with Up */
        FAILED,
        /** Another page is shown (a new tab, a link of another app, a voice search): the one it covered keeps its video paused */
        PAGE,
        /** The screen went away (the activity ends, or the picture-in-picture window gives its player back) */
        CLOSED,
    }

    private val vb = ViewPlayerScreenBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var callback: Callback
    private lateinit var scope: LifecycleCoroutineScope
    /** Replaced when the playing one goes to the picture-in-picture window or comes back from it */
    private var player = NativePlayer(context)
    private var stateJob: Job? = null
    /**
     * The display follows the frame rate of the video while the setting is on (read when a video opens); the window is
     * the activity's, under the wrapper of the dark theme of the view
     */
    private val frameRates = FrameRateSwitcher(activityOf(context))
    private var matchFrameRate = false

    val isOpen: Boolean get() = isVisible

    /** The video is the one the page plays: Up gives it back to the page */
    private var pageMedia = false
    /** The title of the tab and its site, for the seek bar */
    private var title: String? = null
    private var site: String? = null
    /** The view that had the focus before the panel or the error took it (the fullscreen view of the page) */
    private var focusBefore: View? = null
    /** The page shows through until the first frame of the video */
    private var pageUnder = false
    /** Whether a video the rules opened starts in time */
    private val start = PlayerStart(SystemClock::elapsedRealtime)
    private val startCheck = Runnable { checkStart() }

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (vb.vPanel.isOpen) vb.vPanel.back() else close(Exit.BACK)
        }
    }

    private val keys = VideoModeController(object : VideoModeController.Host {
        override fun hideCursor() {}
        override fun showCursor() {}
        override fun togglePlayback() = player.togglePlayback()
        override fun seekTo(position: Double) = player.seekTo(position)
        override fun seekBy(delta: Double) = player.seekBy(delta)

        override fun renderOsd(osd: VideoModeController.Osd?) = vb.vOsd.render(osd)

        override fun onUpDown(down: Boolean): Boolean {
            if (down) showSettings() else if (pageMedia) close(Exit.SITE_PLAYER)
            return true
        }
    }).apply { hint = context.getString(R.string.player_menu_hint) }

    init {
        visibility = GONE
        showPageUnder(false)
        //above the surface an engine draws its pages into (GeckoView): surfaces of the same layer are stacked in the
        //order they were made in, and the engine makes its own again when the activity comes back (picture-in-picture)
        (vb.vPlayer.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
        //a surface only while a video is shown (see close)
        vb.vPlayer.isVisible = false
        //the screen stays on while the player is shown
        vb.vPlayer.keepScreenOn = true
        vb.btnBack.setOnClickListener { close(if (pageMedia) Exit.SITE_PLAYER else Exit.BACK) }
        vb.btnOtherApp.setOnClickListener {
            val video = player.state.value?.video ?: return@setOnClickListener
            if (callback.openInOtherApp(video)) close(Exit.ELSEWHERE)
        }
        vb.vPanel.onClosed = ::restoreFocus
    }

    fun attach(scope: LifecycleCoroutineScope, callback: Callback) {
        this.scope = scope
        this.callback = callback
    }

    /**
     * Plays [video] from [startPosition] seconds; [pageMedia]: it is the video the page plays (Up gives it back
     * to the page from where the player is); [auto]: the rules opened it, so it goes back to the page if it does
     * not start. [title]: of the tab, [site]: its host, for the seek bar
     */
    fun show(video: PageVideo, startPosition: Double = 0.0, pageMedia: Boolean = false, auto: Boolean = false,
             title: String? = null, site: String? = null) {
        showScreen(video, pageMedia, title, site)
        showPageUnder(true)
        matchFrameRate = AppContext.provideConfig().matchFrameRate
        player.open(video, callback.cookies(video.url), startPosition, ownFrameRate = matchFrameRate)
        start.open(watch = auto)
        scheduleStartCheck()
        bindPlayer()
    }

    /** Shows [player], a video that plays already (back from the picture-in-picture window), without the intro */
    fun adopt(player: NativePlayer, pageMedia: Boolean = false, title: String? = null, site: String? = null) {
        val video = player.state.value?.video
        if (video == null) {
            player.release()
            return
        }
        if (isOpen) close()
        this.player.release()
        this.player = player
        showScreen(video, pageMedia, title, site)
        //it shows its frame at once: no page under it meanwhile
        showPageUnder(false)
        start.stop()
        matchFrameRate = player.ownFrameRate
        bindPlayer()
    }

    /**
     * Until the first frame of the video the screen is transparent and dimmed (the paused page shows through, the
     * frame of its fullscreen video); then black, around a video of another shape
     */
    private fun showPageUnder(under: Boolean) {
        pageUnder = under
        val ground = if (under) Color.TRANSPARENT else Color.BLACK
        setBackgroundColor(ground)
        vb.vPlayer.setShutterBackgroundColor(ground)
        vb.vStartScrim.isVisible = under
    }

    private fun scheduleStartCheck() {
        removeCallbacks(startCheck)
        start.remaining()?.let { postDelayed(startCheck, it) }
    }

    /** A video the rules opened did not start: it goes back to the page */
    private fun checkStart() {
        if (!isOpen) return
        val state = player.state.value
        if (start.check(state?.firstFrame == true, state?.error != null)) close(Exit.FAILED) else scheduleStartCheck()
    }

    private fun showScreen(video: PageVideo, pageMedia: Boolean, title: String?, site: String?) {
        visibility = VISIBLE
        //a new surface, without the frames of the video before
        vb.vPlayer.isVisible = true
        backCallback.isEnabled = true
        this.pageMedia = pageMedia
        this.title = title?.takeIf { it.isNotBlank() } ?: ExternalVideoPlayer.title(video)
        this.site = site?.takeIf { it.isNotEmpty() }
        keys.upHint = if (pageMedia) context.getString(R.string.site_player) else null
        vb.btnBack.setText(if (pageMedia) R.string.player_watch_on_site else R.string.player_back_to_page)
        captionHeight = null
        vb.vOsd.setInfo(this.title, caption(video, null, this.site))
    }

    /** "TV Bro player · 1080p · HLS · site" */
    private fun caption(video: PageVideo, height: Int?, site: String?) =
        PageVideoRowText.playerCaption(context.getString(R.string.built_in_player), video, height, site)

    /** The player never takes the focus while it plays: the page keeps it (the panel and the error take it) */
    private fun bindPlayer() {
        vb.vPlayer.player = player.exoPlayer
        keys.setFullscreen(true)
        stateJob?.cancel()
        stateJob = scope.launch { player.state.collect { render(it) } }
    }

    /** Before the panel or the error takes the focus: the view that has it gets it back when they are gone */
    private fun keepFocus() {
        if (focusBefore != null) return
        focusBefore = rootView.findFocus()?.takeUnless { it.isInside(this) }
    }

    /** The panel or the error is gone: the focus goes back to where it was, if they are both gone */
    private fun restoreFocus() {
        if (vb.vPanel.isOpen || vb.flError.isVisible) return
        val view = focusBefore ?: return
        focusBefore = null
        if (view.isAttachedToWindow && view.isShown) view.requestFocus()
    }

    private fun View.isInside(group: View): Boolean {
        var parent: ViewParent? = parent
        while (parent != null) {
            if (parent === group) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * The video goes on in a picture-in-picture window over the browser: the player is handed over playing
     * ([PictureInPicturePlayback]) and the screen closes with a player of its own for the next video. All of it
     * happens before the window starts, since the activity pauses then, which pauses the player of the screen.
     */
    fun enterPictureInPicture() {
        if (!isOpen || player.state.value == null || !PictureInPictureSupport.isAvailable(context)) return
        val playing = player
        vb.vPanel.close()
        stateJob?.cancel()
        stateJob = null
        keys.setFullscreen(false)
        keys.setMedia(null)
        vb.vPlayer.player = null
        hideSurface()
        frameRates.restore()
        player = NativePlayer(context)
        PictureInPicturePlayback.start(playing)
        hideError()
        start.stop()
        removeCallbacks(startCheck)
        visibility = GONE
        backCallback.isEnabled = false
        callback.onClosed(Exit.ELSEWHERE, null)
        callback.openPictureInPicture()
    }

    /** Closes the player; the page does with its video what [exit] says */
    fun close(exit: Exit = Exit.CLOSED) {
        if (!isOpen) return
        //where the page goes on with its video (the state is gone with the player)
        val position = if (pageMedia) player.state.value?.media?.positionAt(SystemClock.elapsedRealtime()) else null
        vb.vPanel.close()
        hideError()
        start.stop()
        removeCallbacks(startCheck)
        stateJob?.cancel()
        stateJob = null
        keys.setFullscreen(false)
        keys.setMedia(null)
        vb.vPlayer.player = null
        player.release()
        hideSurface()
        frameRates.restore()
        visibility = GONE
        backCallback.isEnabled = false
        callback.onClosed(exit, position)
    }

    /**
     * The surface goes with the video: PlayerView hides its SurfaceView with it, which drops the surface. Under a
     * hidden parent it would only be hidden, and show the last frame of this video again under the next one, until
     * its first frame (or under the error, if it never comes).
     */
    private fun hideSurface() {
        vb.vPlayer.isVisible = false
    }

    private fun hideError() {
        if (!vb.flError.isVisible) return
        vb.flError.isVisible = false
        restoreFocus()
    }

    /**
     * The activity is paused: so is the video, and the display gets its mode back. A video that waits for its start
     * then stays here: it does not go back to the page by itself.
     */
    fun onPause() {
        player.pause()
        frameRates.pause()
        start.stop()
        removeCallbacks(startCheck)
    }

    fun onResume() = frameRates.resume()

    /** The height of the video track the caption tells */
    private var captionHeight: Int? = null

    private fun render(state: NativePlayer.State?) {
        if (state == null) return
        if (start.check(state.firstFrame, state.error != null)) {
            //the rules opened it: the page plays the video on, no error is shown
            close(Exit.FAILED)
            return
        }
        if (state.firstFrame && pageUnder) {
            showPageUnder(false)
            callback.onFirstFrame()
        }
        if (state.videoHeight != captionHeight) {
            captionHeight = state.videoHeight
            vb.vOsd.setInfo(title, caption(state.video, state.videoHeight, site))
        }
        if (matchFrameRate) frameRates.request(state.frameRate)
        keys.setMedia(if (state.error == null) state.media else null)
        vb.pbBuffering.isVisible = state.buffering && state.error == null
        val error = state.error
        val showError = error != null
        if (showError != vb.flError.isVisible) {
            if (showError) {
                vb.vPanel.close()
                keepFocus()
                vb.flError.isVisible = true
                vb.btnBack.requestFocus()
            } else {
                hideError()
            }
        }
        if (error != null) vb.tvError.text = describe(error)
    }

    /** Why the video does not play, in words: the status of the server, the network, the format, DRM */
    private fun describe(error: PlaybackException): String = when (val failure = PlaybackFailure.of(error)) {
        is PlaybackFailure.Refused -> context.getString(R.string.player_error_refused, failure.status)
        is PlaybackFailure.NotFound -> context.getString(R.string.player_error_not_found, failure.status)
        is PlaybackFailure.Server -> context.getString(R.string.player_error_server, failure.status)
        PlaybackFailure.Network -> context.getString(R.string.player_error_network)
        PlaybackFailure.Format -> context.getString(R.string.player_error_format)
        PlaybackFailure.Protected -> context.getString(R.string.player_error_protected)
        is PlaybackFailure.Other -> context.getString(R.string.player_error_other, failure.name)
    }

    /** The media keys of the remote, while the player is open; @return true if the player took it */
    fun mediaCommand(command: MediaCommand): Boolean {
        if (!isOpen) return false
        player.mediaCommand(command)
        return true
    }

    /**
     * The keys while the player is open (before the views get them); the panel and the error take the navigation
     * keys for their rows and buttons. Keys of no use here go nowhere, not to the page under the player.
     */
    fun dispatchPlayerKey(event: KeyEvent): Boolean {
        if (!isOpen) return false
        if (vb.vPanel.isOpen || vb.flError.isVisible) return false
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showSettings()
            return true
        }
        //the PiP key of remotes; the system keeps it to itself while a picture-in-picture window is shown
        if (event.keyCode == KeyEvent.KEYCODE_WINDOW && PictureInPictureSupport.isAvailable(context)) {
            if (event.action == KeyEvent.ACTION_UP) enterPictureInPicture()
            return true
        }
        if (keys.dispatchKeyEvent(event)) return true
        return event.keyCode in SWALLOWED_KEYS
    }

    private val texts = PlayerTracks.Texts(context.getString(R.string.player_auto), context.getString(R.string.player_off),
        context.getString(R.string.player_captions_in_video)) {
        context.getString(R.string.player_track, it)
    }

    /** The video settings, the focus on [focus] (a row of the first page) */
    private fun showSettings(focus: Int = 0) {
        val page = settingsPage(focus) ?: return
        keepFocus()
        vb.vPanel.open(page)
    }

    /**
     * The first page of the video settings: the categories the video has a choice in (each opens the page of its
     * options; a choice goes back here at that row), then the actions; null while no video is open
     */
    private fun settingsPage(focus: Int): PlayerPanelView.Page? {
        val state = player.state.value ?: return null
        val parameters = player.trackSelection ?: return null
        val rows = ArrayList<PlayerPanelView.Row>()
        fun choice(icon: Int, name: String, value: String, options: List<Pair<String, Boolean>>, choose: (Int) -> Unit) {
            val index = rows.size
            rows += PlayerPanelView.Row(icon, name, value = value, chevron = true) {
                vb.vPanel.push(PlayerPanelView.Page(name, null, options.mapIndexed { i, (label, checked) ->
                    PlayerPanelView.Row(R.drawable.ic_check, label, checked = checked) {
                        choose(i)
                        settingsPage(index)?.let { vb.vPanel.open(it) }
                    }
                }, context.getString(R.string.player_panel_back), options.indexOfFirst { it.second }.coerceAtLeast(0)))
            }
        }
        fun tracks(icon: Int, name: Int, type: Int, options: List<PlayerTracks.Option>) {
            val current = options.firstOrNull { it.selected }?.label ?: return
            choice(icon, context.getString(name), current, options.map { it.label to it.selected }) {
                player.choose(type, options[it].choice)
            }
        }
        tracks(R.drawable.ic_quality, R.string.player_quality, C.TRACK_TYPE_VIDEO, PlayerTracks.qualities(state.tracks, parameters, texts))
        tracks(R.drawable.ic_audio_track, R.string.player_audio, C.TRACK_TYPE_AUDIO, PlayerTracks.audioTracks(state.tracks, parameters, texts))
        tracks(R.drawable.ic_subtitles, R.string.player_subtitles, C.TRACK_TYPE_TEXT,
            PlayerTracks.subtitles(state.tracks, parameters, texts))
        val speed = player.speed
        choice(R.drawable.ic_speed, context.getString(R.string.player_speed), PlayerTracks.speedLabel(speed),
            PlayerTracks.SPEEDS.map { PlayerTracks.speedLabel(it) to (it == speed) }) { player.setSpeed(PlayerTracks.SPEEDS[it]) }
        //a live stream has no end to start again from
        if (state.media.duration != null) {
            val looping = player.looping
            val labels = listOf(context.getString(R.string.player_repeat_off), context.getString(R.string.player_repeat_on))
            choice(R.drawable.ic_repeat, context.getString(R.string.player_repeat), labels[if (looping) 1 else 0],
                listOf(labels[0] to !looping, labels[1] to looping)) { player.looping = it == 1 }
        }
        if (PictureInPictureSupport.isAvailable(context)) {
            rows += PlayerPanelView.Row(R.drawable.ic_picture_in_picture, context.getString(R.string.player_picture_in_picture)) {
                enterPictureInPicture()
            }
        }
        //the player of the fullscreen videos of the site: for the video of the page only
        val site = if (pageMedia) callback.sitePlayer() else null
        if (site != null) {
            //the player its videos get, also by the default (the page of the options tells which)
            val value = context.getString(if (site.builtIn) R.string.player_for_site_built_in else R.string.player_for_site_site)
            val index = rows.size
            rows += PlayerPanelView.Row(R.drawable.ic_globe, context.getString(R.string.player_for_site), value = value,
                chevron = true, dividerBefore = true) { vb.vPanel.push(sitePlayerPage(site, index)) }
        }
        rows += PlayerPanelView.Row(R.drawable.ic_open_in_new, context.getString(R.string.player_open_in_other_app),
            dividerBefore = site == null) {
            if (callback.openInOtherApp(state.video)) close(Exit.ELSEWHERE)
        }
        //the player plays on while the file downloads
        if (state.video.kind == PageVideo.Kind.FILE) {
            rows += PlayerPanelView.Row(R.drawable.ic_downloads, context.getString(R.string.download)) {
                callback.download(state.video)
            }
        }
        return PlayerPanelView.Page(context.getString(R.string.player_menu_hint), caption(state.video, state.videoHeight, null),
            rows, context.getString(R.string.player_panel_close), focus.coerceIn(0, rows.lastIndex))
    }

    /**
     * Default, this player, the player of the site, each with what it is good for; the choice is kept for the site,
     * and one that gives its videos to the site takes this one back to the page, from the same second
     */
    private fun sitePlayerPage(site: SitePlayer, settingsRow: Int): PlayerPanelView.Page {
        val options = listOf(null, true, false)
        val labels = listOf(context.getString(R.string.player_for_site_default), context.getString(R.string.built_in_player),
            context.getString(R.string.site_player))
        val captions = listOf(context.getString(if (site.byDefault) R.string.player_default_built_in else R.string.player_default_site),
            context.getString(R.string.built_in_player_summary), context.getString(R.string.site_player_summary))
        return PlayerPanelView.Page(context.getString(R.string.player_for_site), site.host, options.mapIndexed { i, option ->
            PlayerPanelView.Row(R.drawable.ic_check, labels[i], caption = captions[i], checked = option == site.setting) {
                callback.setSitePlayer(option)
                if (!(option ?: site.byDefault)) close(Exit.SITE_PLAYER) else settingsPage(settingsRow)?.let { vb.vPanel.open(it) }
            }
        }, context.getString(R.string.player_panel_back), options.indexOf(site.setting))
    }

    companion object {
        /** The activity a (themed) context of a view belongs to */
        private fun activityOf(context: Context): Activity {
            var c = context
            while (c !is Activity) c = (c as? ContextWrapper)?.baseContext ?: error("No activity for $context")
            return c
        }

        private val SWALLOWED_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MENU)
    }
}
