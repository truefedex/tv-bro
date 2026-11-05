package com.phlox.tvwebbrowser.activity.main.view

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.phlox.tvwebbrowser.activity.main.PageVideoRowText
import com.phlox.tvwebbrowser.activity.main.VideoModeController
import com.phlox.tvwebbrowser.databinding.ViewPlayerScreenBinding
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.player.FrameRateSwitcher
import com.phlox.tvwebbrowser.player.NativePlayer
import com.phlox.tvwebbrowser.player.PictureInPicturePlayback
import com.phlox.tvwebbrowser.player.PictureInPictureSupport
import com.phlox.tvwebbrowser.player.PlayerStart
import com.phlox.tvwebbrowser.player.PlayerTracks
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.PageVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The built-in player on TV, over the whole window ([NativePlayer]). The keys are those of the video mode
 * ([VideoModeController]: Left and Right seek, faster while held, OK plays and pauses, the seek bar shows on
 * seeks, on play and pause and while paused, with the plate at the top: the title, and under it the name of the
 * player, the height of the video track, the kind and the server); Down and the Menu key (where it reaches
 * the app) open the menu of the quality, the audio track, the subtitles, the speed ([PlayerTracks]) and the repeat
 * of the video (not for live streams, off for each video), each with its options, and the seek bar tells it
 * ("↓ Video settings"). For the video of the page ([show] with pageMedia) Up gives it back to the page from where
 * the player is ("↑ Site player"); the page stays in fullscreen under the player meanwhile. Back closes the menu,
 * then the player ([Callback.onClosed] tells the page how, [Exit]). The player takes the focus only for the menu and
 * the error, and gives it back to the view that had it (the fullscreen view of the page). Until the first frame of
 * the video the screen is transparent: the paused page shows through, dimmed, under the spinner. A video that the
 * rules opened by themselves ([show] with auto, [com.phlox.tvwebbrowser.activity.main.PlayerChoice]) goes back to the
 * page when it does not start ([PlayerStart], [Exit.FAILED]); for others, a failed playback shows the error, which
 * offers the video to another app and the way back. Where the device has picture-in-picture
 * ([PictureInPictureSupport]), the menu and the PiP key of remotes hand the playing video to a picture-in-picture
 * window over the browser ([enterPictureInPicture], [PictureInPicturePlayback]), and it comes back here when the
 * window is expanded ([adopt]).
 */
class PlayerScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    interface Callback {
        /** The cookies of the engine for the address */
        fun cookies(url: String): String?
        /** Opens the video in another app; false if there is none */
        fun openInOtherApp(video: PageVideo): Boolean
        /**
         * The player closed ([exit]); [position] is where it was, in seconds, when it played the video of the page
         * (null for another video)
         */
        fun onClosed(exit: Exit, position: Double?)
        /** Starts the picture-in-picture window of the player handed to [PictureInPicturePlayback] */
        fun openPictureInPicture()
    }

    /** How the player closed: what the page does with its video then */
    enum class Exit {
        /** Back (and Back to the page of the error): the page leaves fullscreen, its video waits where the player was */
        BACK,
        /** Up: the page plays its video on from where the player was */
        SITE_PLAYER,
        /** The video went on in the picture-in-picture window or in another app: the page leaves fullscreen */
        ELSEWHERE,
        /** The rules opened the player and it did not start: the page plays its video on, as with Up */
        FAILED,
        /** The screen went away (the activity ends, or the picture-in-picture window gives its player back) */
        CLOSED,
    }

    private val vb = ViewPlayerScreenBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var callback: Callback
    private lateinit var scope: LifecycleCoroutineScope
    /** Replaced when the playing one goes to the picture-in-picture window or comes back from it */
    private var player = NativePlayer(context)
    private var stateJob: Job? = null
    /** The display follows the frame rate of the video while the setting is on (read when a video opens) */
    private val frameRates = FrameRateSwitcher(context as Activity)
    private var matchFrameRate = false

    val isOpen: Boolean get() = isVisible

    /** The video is the one the page plays: Up gives it back to the page */
    private var pageMedia = false
    /** The view that had the focus before the menu or the error took it (the fullscreen view of the page) */
    private var focusBefore: View? = null
    /** The page shows through until the first frame of the video */
    private var pageUnder = false
    /** Whether a video the rules opened starts in time */
    private val start = PlayerStart(SystemClock::elapsedRealtime)
    private val startCheck = Runnable { checkStart() }

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (vb.vMenu.isOpen) vb.vMenu.hide() else close(Exit.BACK)
        }
    }

    private val keys = VideoModeController(object : VideoModeController.Host {
        override fun hideCursor() {}
        override fun showCursor() {}
        override fun togglePlayback() = player.togglePlayback()
        override fun seekTo(position: Double) = player.seekTo(position)
        override fun seekBy(delta: Double) = player.seekBy(delta)

        override fun renderOsd(osd: VideoModeController.Osd?) {
            vb.vOsd.render(osd)
            vb.llTitle.isVisible = osd != null
        }

        override fun onUpDown(down: Boolean): Boolean {
            if (down) showMenu() else if (pageMedia) close(Exit.SITE_PLAYER)
            return true
        }
    }).apply { hint = context.getString(R.string.player_menu_hint) }

    init {
        visibility = GONE
        showPageUnder(false)
        //the screen stays on while the player is shown
        vb.vPlayer.keepScreenOn = true
        vb.btnBack.setOnClickListener { close(Exit.BACK) }
        vb.btnOtherApp.setOnClickListener {
            val video = player.state.value?.video ?: return@setOnClickListener
            if (callback.openInOtherApp(video)) close(Exit.ELSEWHERE)
        }
        vb.vMenu.onHide = ::restoreFocus
    }

    fun attach(scope: LifecycleCoroutineScope, callback: Callback) {
        this.scope = scope
        this.callback = callback
    }

    /**
     * Plays [video] from [startPosition] seconds; [pageMedia]: it is the video the page plays (Up gives it back
     * to the page from where the player is); [auto]: the rules opened it, so it goes back to the page if it does
     * not start
     */
    fun show(video: PageVideo, startPosition: Double = 0.0, pageMedia: Boolean = false, auto: Boolean = false) {
        showScreen(video, pageMedia)
        showPageUnder(true)
        matchFrameRate = AppContext.provideConfig().matchFrameRate
        player.open(video, callback.cookies(video.url), startPosition, ownFrameRate = matchFrameRate)
        start.open(watch = auto)
        scheduleStartCheck()
        bindPlayer()
    }

    /** Shows [player], a video that plays already (back from the picture-in-picture window), without the intro */
    fun adopt(player: NativePlayer, pageMedia: Boolean = false) {
        val video = player.state.value?.video
        if (video == null) {
            player.release()
            return
        }
        if (isOpen) close()
        this.player.release()
        this.player = player
        showScreen(video, pageMedia)
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

    private fun showScreen(video: PageVideo, pageMedia: Boolean) {
        visibility = VISIBLE
        backCallback.isEnabled = true
        this.pageMedia = pageMedia
        keys.upHint = if (pageMedia) context.getString(R.string.site_player) else null
        vb.tvTitle.text = ExternalVideoPlayer.title(video)
        captionHeight = null
        vb.tvCaption.text = PageVideoRowText.playerCaption(context.getString(R.string.built_in_player), video, null)
    }

    /** The player never takes the focus while it plays: the page keeps it (the menu and the error take it) */
    private fun bindPlayer() {
        vb.vPlayer.player = player.exoPlayer
        keys.setFullscreen(true)
        stateJob?.cancel()
        stateJob = scope.launch { player.state.collect { render(it) } }
    }

    /** Before the menu or the error takes the focus: the view that has it gets it back when they are gone */
    private fun keepFocus() {
        if (focusBefore != null) return
        focusBefore = rootView.findFocus()?.takeUnless { it.isInside(this) }
    }

    /** The menu or the error is gone: the focus goes back to where it was, if they are both gone */
    private fun restoreFocus() {
        if (vb.vMenu.isOpen || vb.llError.isVisible) return
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
        vb.vMenu.hide()
        stateJob?.cancel()
        stateJob = null
        keys.setFullscreen(false)
        keys.setMedia(null)
        vb.vPlayer.player = null
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
        vb.vMenu.hide()
        hideError()
        start.stop()
        removeCallbacks(startCheck)
        stateJob?.cancel()
        stateJob = null
        keys.setFullscreen(false)
        keys.setMedia(null)
        vb.vPlayer.player = null
        player.release()
        frameRates.restore()
        visibility = GONE
        backCallback.isEnabled = false
        callback.onClosed(exit, position)
    }

    private fun hideError() {
        if (!vb.llError.isVisible) return
        vb.llError.isVisible = false
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
        if (state.firstFrame && pageUnder) showPageUnder(false)
        if (state.videoHeight != captionHeight) {
            captionHeight = state.videoHeight
            vb.tvCaption.text = PageVideoRowText.playerCaption(context.getString(R.string.built_in_player),
                state.video, state.videoHeight)
        }
        if (matchFrameRate) frameRates.request(state.frameRate)
        keys.setMedia(if (state.error == null) state.media else null)
        vb.pbBuffering.isVisible = state.buffering && state.error == null
        val error = state.error
        val showError = error != null
        if (showError != vb.llError.isVisible) {
            if (showError) {
                vb.vMenu.hide()
                keepFocus()
                vb.llError.isVisible = true
                vb.btnOtherApp.requestFocus()
            } else {
                hideError()
            }
        }
        if (error != null) vb.tvError.text = describe(error)
    }

    /** The HTTP status for an error of the server (as "HTTP 404"), else the name of the error of Media3 */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP ${cause.responseCode}"
            cause = cause.cause
        }
        return error.errorCodeName
    }

    /** The media keys of the remote, while the player is open; @return true if the player took it */
    fun mediaCommand(command: MediaCommand): Boolean {
        if (!isOpen) return false
        player.mediaCommand(command)
        return true
    }

    /**
     * The keys while the player is open (before the views get them); the error panel takes the navigation
     * keys for its buttons. Keys of no use here go nowhere, not to the page under the player.
     */
    fun dispatchPlayerKey(event: KeyEvent): Boolean {
        if (!isOpen) return false
        //the menu and the error take the navigation keys for their rows and buttons
        if (vb.vMenu.isOpen || vb.llError.isVisible) return false
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
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

    private val texts = PlayerTracks.Texts(context.getString(R.string.player_auto), context.getString(R.string.player_off)) {
        context.getString(R.string.player_track, it)
    }

    /** The categories the video has a choice in; each opens the menu of its options */
    private fun showMenu() {
        val state = player.state.value ?: return
        val parameters = player.trackSelection ?: return
        val rows = ArrayList<PopupMenuView.Item>()
        fun category(icon: Int, name: Int, type: Int, options: List<PlayerTracks.Option>) {
            if (options.isEmpty()) return
            val current = options.firstOrNull { it.selected }?.label ?: return
            rows += PopupMenuView.Item(icon, context.getString(R.string.player_menu_row, context.getString(name), current)) {
                showOptions(options.map { option ->
                    PopupMenuView.Item(R.drawable.ic_check, option.label, checked = option.selected) { player.choose(type, option.choice) }
                }, options.indexOfFirst { it.selected })
            }
        }
        category(R.drawable.ic_quality, R.string.player_quality, C.TRACK_TYPE_VIDEO,
            PlayerTracks.qualities(state.tracks, parameters, texts))
        category(R.drawable.ic_audio_track, R.string.player_audio, C.TRACK_TYPE_AUDIO, PlayerTracks.audioTracks(state.tracks, texts))
        category(R.drawable.ic_subtitles, R.string.player_subtitles, C.TRACK_TYPE_TEXT,
            PlayerTracks.subtitles(state.tracks, parameters, texts))
        val speed = player.speed
        rows += PopupMenuView.Item(R.drawable.ic_speed,
            context.getString(R.string.player_menu_row, context.getString(R.string.player_speed), PlayerTracks.speedLabel(speed))) {
            showOptions(PlayerTracks.SPEEDS.map { option ->
                PopupMenuView.Item(R.drawable.ic_check, PlayerTracks.speedLabel(option), checked = option == speed) { player.setSpeed(option) }
            }, PlayerTracks.SPEEDS.indexOf(speed).coerceAtLeast(0))
        }
        //a live stream has no end to start again from
        if (state.media.duration != null) {
            val looping = player.looping
            fun label(on: Boolean) = context.getString(if (on) R.string.player_repeat_on else R.string.player_repeat_off)
            rows += PopupMenuView.Item(R.drawable.ic_repeat,
                context.getString(R.string.player_menu_row, context.getString(R.string.player_repeat), label(looping))) {
                showOptions(listOf(false, true).map { option ->
                    PopupMenuView.Item(R.drawable.ic_check, label(option), checked = option == looping) { player.looping = option }
                }, if (looping) 1 else 0)
            }
        }
        if (PictureInPictureSupport.isAvailable(context)) {
            rows += PopupMenuView.Item(R.drawable.ic_picture_in_picture, context.getString(R.string.player_picture_in_picture)) {
                enterPictureInPicture()
            }
        }
        showOptions(rows, 0)
    }

    private fun showOptions(items: List<PopupMenuView.Item>, focusIndex: Int) {
        keepFocus()
        vb.vMenu.show(vb.vMenuAnchor, items, alignEnd = true, width = R.dimen.player_menu_width,
            focusIndex = focusIndex.coerceAtLeast(0))
    }

    companion object {
        private val SWALLOWED_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MENU)
    }
}
