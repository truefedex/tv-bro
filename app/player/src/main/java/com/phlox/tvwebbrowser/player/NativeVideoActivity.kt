package com.phlox.tvwebbrowser.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.UUID

/** Uses the TV decoder with bounded buffers for HTTP, HLS and DASH media. */
@UnstableApi
class NativeVideoActivity : Activity() {
    enum class ReturnAction { BACK, ENDED, FAILED }
    data class PlaybackResult(val positionMs: Long, val action: ReturnAction)
    data class Subtitle(val url: String, val language: String, val label: String)
    data class PlaybackRequest(val source: MediaRequestCatalog.Request, val context: MediaRequestContext,
        val positionMs: Long, val speed: Float, val volume: Float = 1f, val textEnabled: Boolean = false,
        val textLanguage: String = "", val subtitles: List<Subtitle> = emptyList())
    companion object {
        private const val TAG = "TVBroPlayback"
        // The web engine may still hold audio focus when we start. Re-issue play a few times
        // until focus is available instead of leaving the user with a paused picture.
        private const val PLAY_RETRY_MS = 600L
        private const val MAX_PLAY_RETRIES = 12
        // A stream that played for at least this long before failing counts as a new incident and
        // gets a fresh retry budget, instead of a crash-loop burning all attempts in seconds.
        private const val STABLE_PLAYBACK_MS = 10_000L
        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        private data class Handoff(val token: String, val request: PlaybackRequest, val callback: (PlaybackResult) -> Unit)
        private var handoff: Handoff? = null

        fun launch(context: Context, request: PlaybackRequest,
                   callback: (PlaybackResult) -> Unit) {
            check(handoff == null) { "A native playback handoff is already active" }
            val token = UUID.randomUUID().toString()
            handoff = Handoff(token, request, callback)
            try {
                context.startActivity(Intent(context, NativeVideoActivity::class.java)
                    .putExtra("handoffToken", token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                handoff = null
                throw e
            }
        }
    }

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var position = 0L
    private var playWhenReady = true
    private var decoder = ""
    private var returnAction = ReturnAction.BACK
    private var resizeModeIndex = 0
    private var request: PlaybackRequest? = null
    // BACK leaves the player only on the second press: the first brings the controls up. Track the
    // controller visibility ourselves because PlayerView exposes no plain "is the controller shown".
    private var controlsVisible = false
    private var backConsumedDown = false
    private var playRequested = true
    private var suppressUserIntent = false
    private var playRetries = 0
    // Bounded re-prepare attempts for transient playback errors; see PlaybackRetryPolicy.
    private var errorRetries = 0
    private var playingSince = 0L
    private var lastPlayDurationMs = 0L
    private var errorPositionMs = -1L
    private var resumeAfterErrorMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val ensurePlaybackRunnable = Runnable { ensurePlayback() }
    private val retryPlaybackRunnable = Runnable { retryPlayback() }
    private val bufferTimeout = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true) {
            val pos = player?.currentPosition ?: 0L
            Log.w(TAG, "bufferTimeout positionMs=$pos")
            noteFailure()
            if (!scheduleRetry(pos)) {
                returnAction = ReturnAction.FAILED
                finish()
            }
        }
    }
    private val sample = object : Runnable {
        override fun run() {
            player?.let { p ->
                val counters = p.videoDecoderCounters
                counters?.ensureUpdated()
                val format = p.videoFormat
                Log.i(TAG, JSONObject().put("positionMs", p.currentPosition)
                    .put("bufferMs", p.totalBufferedDuration).put("state", p.playbackState)
                    .put("playing", p.isPlaying).put("decoder", decoder)
                    .put("speed", p.playbackParameters.speed).put("volume", p.volume)
                    .put("textSelected", p.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT))
                    .put("width", format?.width).put("height", format?.height)
                    .put("fps", format?.frameRate).put("rendered", counters?.renderedOutputBufferCount)
                    .put("dropped", counters?.droppedBufferCount)
                    .put("maxConsecutiveDropped", counters?.maxConsecutiveDroppedBufferCount)
                    .put("skipped", counters?.skippedOutputBufferCount).toString())
            }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        request = handoff?.takeIf { it.token == intent.getStringExtra("handoffToken") }?.request
        if (request == null) { finish(); return }
        position = savedInstanceState?.getLong("position") ?: request!!.positionMs
        playWhenReady = savedInstanceState?.getBoolean("playWhenReady") ?: true
        playerView = PlayerView(this).apply {
            controllerShowTimeoutMs = 2500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    controlsVisible = visibility == View.VISIBLE
                }
            )
        }
        setContentView(playerView)
        playerView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val current = request ?: run { finish(); return }
        val http = ScopedMediaDataSource.Factory(current.context)
        val loadControl = DefaultLoadControl.Builder()
            // The device test exhausted a 15-second low-water mark during a CDN stall.
            // Hold more compressed media while retaining the same 24 MiB allocator limit.
            .setBufferDurationsMs(30000, 60000, 1500, 5000)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            // A fast previous CDN (or LAN stream) says nothing about the next website.
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(this).build())
            .setLoadControl(loadControl).build()
        player = p
        playerView.player = p
        p.setAudioAttributes(AudioAttributes.DEFAULT, true)
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoder = decoderName
                Log.i(TAG, "decoder=$decoderName initializationMs=$initializationDurationMs")
            }
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime,
                bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                Log.w(TAG, "audioUnderrun bufferMs=$bufferSizeMs elapsedMs=$elapsedSinceLastFeedMs")
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                // Remember where we were so a retry can resume there instead of from scratch.
                errorPositionMs = eventTime.currentPlaybackPositionMs
            }
        })
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "state=$playbackState positionMs=${p.currentPosition}")
                scheduleBufferTimeout()
                if (playbackState == Player.STATE_ENDED) {
                    returnAction = ReturnAction.ENDED
                    finish()
                } else if (playbackState == Player.STATE_READY) {
                    handler.postDelayed(ensurePlaybackRunnable, PLAY_RETRY_MS)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "isPlaying=$isPlaying positionMs=${p.currentPosition}")
                if (isPlaying) {
                    playRetries = 0
                    if (playingSince == 0L) playingSince = SystemClock.elapsedRealtime()
                } else if (playingSince != 0L) {
                    lastPlayDurationMs = SystemClock.elapsedRealtime() - playingSince
                    playingSince = 0L
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                scheduleBufferTimeout()
                if (!suppressUserIntent && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    playRequested = playWhenReady
                }
                if (!playWhenReady && playRequested &&
                    reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    handler.postDelayed(ensurePlaybackRunnable, PLAY_RETRY_MS)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "errorCode=${error.errorCodeName}")
                noteFailure()
                if (!PlaybackRetryPolicy.isTransient(error.errorCode) ||
                    !scheduleRetry(resumePositionFor(error))) {
                    returnAction = ReturnAction.FAILED
                    finish()
                }
            }
        })
        val subtitles = current.subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                .setMimeType(if (Uri.parse(subtitle.url).path.orEmpty().endsWith(".ttml", true)) MimeTypes.APPLICATION_TTML else MimeTypes.TEXT_VTT)
                .setLanguage(subtitle.language).setLabel(subtitle.label).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        }
        p.setMediaItem(MediaItem.Builder().setUri(current.source.url).setMimeType(current.source.kind.mime)
            .setSubtitleConfigurations(subtitles).build())
        p.volume = current.volume
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !current.textEnabled)
            .setPreferredTextLanguage(current.textLanguage.ifEmpty { null }).build()
        p.setPlaybackSpeed(current.speed)
        if (position >= 0) p.seekTo(position) else p.seekToDefaultPosition()
        p.prepare()
        playRequested = playWhenReady
        playRetries = 0
        p.playWhenReady = playWhenReady
        handler.post(sample)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in PlayerKeys.SETTINGS) {
            if (event.action == KeyEvent.ACTION_DOWN) showSettings()
            return true
        }
        if (!::playerView.isInitialized) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Two presses to close: the first only reveals the controls, so a stray BACK cannot
            // interrupt playback; the second leaves the player and hands the position to the page.
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!controlsVisible) {
                    backConsumedDown = true
                    playerView.showController()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_UP && backConsumedDown) {
                backConsumedDown = false
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /**
     * Subtitles, audio tracks and video scaling, reachable with the menu key.
     * ExoPlayer renders subtitles by itself once a text track is selected, but it
     * does not pick one on its own, so this is how the user turns them on.
     */
    private fun showSettings() {
        if (player == null) return
        val labels = arrayOf(
            getString(R.string.player_subtitles),
            getString(R.string.player_audio_track),
            getString(R.string.player_video_scale)
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showTrackChooser(C.TRACK_TYPE_TEXT)
                    1 -> showTrackChooser(C.TRACK_TYPE_AUDIO)
                    2 -> cycleResizeMode()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTrackChooser(trackType: Int) {
        val exo = player ?: return
        //flatten the selectable tracks of this type into a single list
        val entries = ArrayList<Pair<Tracks.Group, Int>>()
        for (group in exo.currentTracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) entries.add(group to i)
            }
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.player_no_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = ArrayList<String>()
        if (trackType == C.TRACK_TYPE_TEXT) labels.add(getString(R.string.player_track_off))
        for ((group, i) in entries) {
            val format = group.getTrackFormat(i)
            val name = format.label ?: format.language ?: format.sampleMimeType ?: "?"
            labels.add(name)
        }
        val title = if (trackType == C.TRACK_TYPE_TEXT) R.string.player_subtitles
            else R.string.player_audio_track
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                val offset = if (trackType == C.TRACK_TYPE_TEXT) 1 else 0
                if (trackType == C.TRACK_TYPE_TEXT && which == 0) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, true)
                        .build()
                    return@setItems
                }
                val (group, index) = entries[which - offset]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % RESIZE_MODES.size
        playerView.resizeMode = RESIZE_MODES[resizeModeIndex]
        val name = when (RESIZE_MODES[resizeModeIndex]) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.player_scale_zoom
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.player_scale_fill
            else -> R.string.player_scale_fit
        }
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
    }

    private fun scheduleBufferTimeout() {
        handler.removeCallbacks(bufferTimeout)
        if (player?.playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true)
            handler.postDelayed(bufferTimeout, 15000)
    }

    /**
     * Playback can be held back when the web engine has not released audio focus yet. Re-issue
     * play (after a pause, which is what actually re-runs the focus request) until it starts.
     */
    private fun ensurePlayback() {
        val p = player ?: return
        if (!playRequested || p.isPlaying || p.playbackState != Player.STATE_READY) return
        if (playRetries >= MAX_PLAY_RETRIES) return
        playRetries++
        Log.i(TAG, "rearm playback retry=$playRetries")
        suppressUserIntent = true
        try {
            p.pause()
            p.play()
        } finally {
            suppressUserIntent = false
        }
        handler.postDelayed(ensurePlaybackRunnable, PLAY_RETRY_MS)
    }

    /**
     * Only a genuinely stable stretch of playback earns a fresh retry budget, so a stream that
     * keeps failing cannot retry forever merely by outlasting the backoff.
     */
    private fun noteFailure() {
        val now = SystemClock.elapsedRealtime()
        val playedNow = if (playingSince != 0L) now - playingSince else 0L
        if (maxOf(lastPlayDurationMs, playedNow) >= STABLE_PLAYBACK_MS) errorRetries = 0
        playingSince = 0L
        lastPlayDurationMs = 0L
    }

    private fun resumePositionFor(error: PlaybackException): Long =
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) -1L
        else errorPositionMs.takeIf { it >= 0 } ?: (player?.currentPosition ?: 0L)

    /**
     * Schedules a re-prepare after this attempt's backoff. Returns false when the retry budget is
     * spent (or we are already leaving), so the caller can give up on the stream.
     */
    private fun scheduleRetry(resumeMs: Long): Boolean {
        if (isFinishing || isDestroyed) return false
        if (errorRetries >= PlaybackRetryPolicy.MAX_ATTEMPTS) return false
        val delay = PlaybackRetryPolicy.backoffMs(errorRetries + 1)
        errorRetries++
        resumeAfterErrorMs = resumeMs
        Log.w(TAG, "reconnecting attempt=$errorRetries/${PlaybackRetryPolicy.MAX_ATTEMPTS} " +
            "delayMs=$delay resumeMs=$resumeMs")
        Toast.makeText(this, getString(R.string.player_reconnecting, errorRetries,
            PlaybackRetryPolicy.MAX_ATTEMPTS), Toast.LENGTH_SHORT).show()
        handler.removeCallbacks(retryPlaybackRunnable)
        handler.postDelayed(retryPlaybackRunnable, delay)
        return true
    }

    private fun retryPlayback() {
        val p = player ?: return
        Log.i(TAG, "retrying playback resumeMs=$resumeAfterErrorMs")
        if (resumeAfterErrorMs >= 0) p.seekTo(resumeAfterErrorMs) else p.seekToDefaultPosition()
        p.prepare()
        p.playWhenReady = true
        scheduleBufferTimeout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playWhenReady", player?.playWhenReady ?: playWhenReady)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        handler.removeCallbacks(sample)
        handler.removeCallbacks(bufferTimeout)
        handler.removeCallbacks(ensurePlaybackRunnable)
        handler.removeCallbacks(retryPlaybackRunnable)
        player?.let { p ->
            position = p.currentPosition
            playWhenReady = p.playWhenReady
            playerView.player = null
            p.release()
        }
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            val current = handoff?.takeIf { it.token == intent.getStringExtra("handoffToken") }
            if (current != null) {
                handoff = null
                val result = PlaybackResult(if (current.request.positionMs < 0) -1 else position, returnAction)
                // ActivityThread removes the old SurfaceView after onDestroy returns.
                handler.post { current.callback(result) }
            }
        }
        super.onDestroy()
    }
}
