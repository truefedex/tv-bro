package com.phlox.tvwebbrowser.activity.main.view

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
 * ("↓ Video settings"); Up does nothing. Back closes the menu, then the player; [onClosed] gives the focus back to
 * the page. When the playback fails, the error offers the video to another app and the way back. Until the user
 * chooses "Don't show again" ([Config.builtInPlayerHintSuppress]), a dialog tells what the player is for when it
 * opens, the video paused meanwhile. Where the device has picture-in-picture ([PictureInPictureSupport]), the menu
 * and the PiP key of remotes hand the playing video to a picture-in-picture window over the browser
 * ([enterPictureInPicture], [PictureInPicturePlayback]), and it comes back here when the window is expanded ([adopt]).
 */
class PlayerScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    interface Callback {
        /** The cookies of the engine for the address */
        fun cookies(url: String): String?
        /** Opens the video in another app; false if there is none */
        fun openInOtherApp(video: PageVideo): Boolean
        fun onClosed()
        /** Starts the picture-in-picture window of the player handed to [PictureInPicturePlayback] */
        fun openPictureInPicture()
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

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (vb.vMenu.isOpen) vb.vMenu.hide() else close()
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
            if (down) showMenu()
            return true
        }
    }).apply { hint = context.getString(R.string.player_menu_hint) }

    init {
        visibility = GONE
        setBackgroundColor(android.graphics.Color.BLACK)
        //the screen stays on while the player is shown
        vb.vPlayer.keepScreenOn = true
        vb.btnBack.setOnClickListener { close() }
        vb.btnOtherApp.setOnClickListener {
            val video = player.state.value?.video ?: return@setOnClickListener
            if (callback.openInOtherApp(video)) close()
        }
    }

    fun attach(scope: LifecycleCoroutineScope, callback: Callback) {
        this.scope = scope
        this.callback = callback
    }

    /** Plays [video] from [startPosition] seconds */
    fun show(video: PageVideo, startPosition: Double = 0.0) {
        showScreen(video)
        val config = AppContext.provideConfig()
        matchFrameRate = config.matchFrameRate
        player.open(video, callback.cookies(video.url), startPosition, ownFrameRate = matchFrameRate)
        if (!config.builtInPlayerHintSuppress) showIntroDialog()
        bindPlayer()
    }

    /** Shows [player], a video that plays already (back from the picture-in-picture window), without the intro */
    fun adopt(player: NativePlayer) {
        val video = player.state.value?.video
        if (video == null) {
            player.release()
            return
        }
        if (isOpen) close()
        this.player.release()
        this.player = player
        showScreen(video)
        matchFrameRate = player.ownFrameRate
        bindPlayer()
    }

    private fun showScreen(video: PageVideo) {
        visibility = VISIBLE
        backCallback.isEnabled = true
        vb.tvTitle.text = ExternalVideoPlayer.title(video)
        captionHeight = null
        vb.tvCaption.text = PageVideoRowText.playerCaption(context.getString(R.string.built_in_player), video, null)
    }

    private fun bindPlayer() {
        vb.vPlayer.player = player.exoPlayer
        keys.setFullscreen(true)
        stateJob?.cancel()
        stateJob = scope.launch { player.state.collect { render(it) } }
        requestFocus()
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
        visibility = GONE
        backCallback.isEnabled = false
        callback.onClosed()
        callback.openPictureInPicture()
    }

    fun close() {
        if (!isOpen) return
        vb.vMenu.hide()
        stateJob?.cancel()
        stateJob = null
        keys.setFullscreen(false)
        keys.setMedia(null)
        vb.vPlayer.player = null
        player.release()
        frameRates.restore()
        visibility = GONE
        backCallback.isEnabled = false
        callback.onClosed()
    }

    /** The activity is paused: so is the video, and the display gets its mode back */
    fun onPause() {
        player.pause()
        frameRates.pause()
    }

    fun onResume() = frameRates.resume()

    /**
     * What the built-in player is for, while the video waits; it shows each time the player opens until the user
     * chooses "Don't show again" (as the hint of the incognito mode)
     */
    private fun showIntroDialog() {
        player.pause()
        AlertDialog.Builder(context)
            .setTitle(R.string.built_in_player)
            .setIcon(R.drawable.ic_play)
            .setMessage(R.string.built_in_player_intro)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.don_t_show_again) { _, _ ->
                AppContext.provideConfig().builtInPlayerHintSuppress = true
            }
            //OK, Don't show again and Back: the video goes on
            .setOnDismissListener { if (isOpen) player.play() }
            .show()
    }

    /** The height of the video track the caption tells */
    private var captionHeight: Int? = null

    private fun render(state: NativePlayer.State?) {
        if (state == null) return
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
            vb.llError.isVisible = showError
            if (showError) vb.btnOtherApp.requestFocus() else requestFocus()
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
        vb.vMenu.show(vb.vMenuAnchor, items, alignEnd = true, width = R.dimen.player_menu_width,
            focusIndex = focusIndex.coerceAtLeast(0))
    }

    companion object {
        private val SWALLOWED_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MENU)
    }
}
