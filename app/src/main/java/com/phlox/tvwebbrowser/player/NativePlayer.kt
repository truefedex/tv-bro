package com.phlox.tvwebbrowser.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The built-in player of the videos of pages ([PageVideo]), on ExoPlayer (Media3), without views: it requests
 * the video with the headers of the page (the Referer of its frame, the User-Agent and the cookies of the engine,
 * as [ExternalVideoPlayer] gives them to other apps) and tells its [state]. It lives on the main thread; [release]
 * it when its screen closes.
 */
class NativePlayer(private val context: Context) {
    /** What the screen of the player shows */
    data class State(
        val video: PageVideo,
        /** Where it plays; the duration is null until it is known, and for a live stream */
        val media: MediaState,
        val buffering: Boolean,
        val ended: Boolean,
        /** The error that stopped the playback; null while it plays */
        val error: PlaybackException? = null,
        val tracks: Tracks = Tracks.EMPTY,
        /** Frames per second of the video track playing; null while unknown */
        val frameRate: Float? = null,
    )

    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow<State?>(null)
    /** Null while no video is open */
    val state: StateFlow<State?> = _state.asStateFlow()
    private var video: PageVideo? = null

    /** The ExoPlayer of the open video, for the view that shows it; null while no video is open */
    val exoPlayer: Player? get() = player

    /**
     * Plays [video] from [startPosition] seconds; [cookies]: of the engine, for the address of the video.
     * [ownFrameRate]: the app switches the mode of the display itself ([FrameRateSwitcher]), so the player does
     * not ask the surface for the frame rate (Media3 does it where the switch is seamless)
     */
    fun open(video: PageVideo, cookies: String?, startPosition: Double = 0.0, ownFrameRate: Boolean = false) {
        release()
        this.video = video
        val headers = ExternalVideoPlayer.headers(video, cookies)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers - USER_AGENT)
        headers[USER_AGENT]?.let { http.setUserAgent(it) }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
        this.player = player
        if (ownFrameRate) player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        player.addListener(listener)
        val item = MediaItem.Builder().setUri(video.url).setMimeType(mimeType(video.kind)).build()
        player.setMediaItem(item, (startPosition * 1000).toLong().coerceAtLeast(0))
        player.playWhenReady = true
        player.prepare()
        update()
    }

    fun togglePlayback() {
        val player = player ?: return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            player.playerError != null -> {
                player.prepare()
                player.play()
            }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(position: Double) {
        val player = player ?: return
        player.seekTo((position * 1000).toLong().coerceAtLeast(0))
    }

    fun seekBy(delta: Double) {
        val player = player ?: return
        seekTo(player.currentPosition / 1000.0 + delta)
    }

    /** The selection of the tracks the user made (none: the choices of the player) */
    val trackSelection: TrackSelectionParameters? get() = player?.trackSelectionParameters

    val speed: Float get() = player?.playbackParameters?.speed ?: 1f

    /** A choice of the menu ([PlayerTracks]) for the tracks of [type] (C.TRACK_TYPE_VIDEO, AUDIO or TEXT) */
    fun choose(type: Int, choice: PlayerTracks.Choice) {
        val player = player ?: return
        val parameters = player.trackSelectionParameters.buildUpon()
        when (choice) {
            PlayerTracks.Choice.Auto -> parameters.clearOverridesOfType(type)
            PlayerTracks.Choice.Off -> parameters.setTrackTypeDisabled(type, true)
            is PlayerTracks.Choice.Track -> parameters.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
            is PlayerTracks.Choice.Group -> parameters.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(choice.group, (0 until choice.group.length).toList()))
        }
        player.trackSelectionParameters = parameters.build()
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    /** Stops the playback and frees the decoders; [state] becomes null */
    fun release() {
        player?.removeListener(listener)
        player?.release()
        player = null
        video = null
        _state.value = null
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    private fun update() {
        val player = player ?: return
        val video = video ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 && !player.isCurrentMediaItemLive }
        val ended = player.playbackState == Player.STATE_ENDED
        _state.value = State(
            video = video,
            media = MediaState(
                playing = player.playWhenReady && !ended,
                position = player.currentPosition / 1000.0,
                duration = duration?.let { it / 1000.0 },
                fullscreen = true,
                rate = if (player.isPlaying) player.playbackParameters.speed.toDouble() else 0.0,
                at = SystemClock.elapsedRealtime(),
            ),
            buffering = player.playbackState == Player.STATE_BUFFERING,
            ended = ended,
            error = player.playerError,
            tracks = player.currentTracks,
            frameRate = (player as? ExoPlayer)?.videoFormat?.frameRate?.takeIf { it > 0 },
        )
    }

    companion object {
        private const val USER_AGENT = "User-Agent"

        /** Streams by their manifests; files by what the server says */
        fun mimeType(kind: PageVideo.Kind): String? = when (kind) {
            PageVideo.Kind.HLS -> MimeTypes.APPLICATION_M3U8
            PageVideo.Kind.DASH -> MimeTypes.APPLICATION_MPD
            PageVideo.Kind.FILE -> null
        }
    }
}
