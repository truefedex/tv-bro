package com.phlox.tvwebbrowser.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
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
import com.phlox.tvwebbrowser.activity.main.VideoModeController
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The built-in player of the videos of pages ([PageVideo]), on ExoPlayer (Media3), without views: it requests
 * the video with the headers of the page (the Referer of its frame, the User-Agent and the cookies of the engine,
 * as [ExternalVideoPlayer] gives them to other apps) and tells its [state]. It lives on the main thread; [release]
 * it when its screen closes. It holds the application context only, so it can go from the screen of the browser to
 * the picture-in-picture window and back ([PictureInPicturePlayback]). It takes the audio focus: the sound of another
 * app pauses it.
 */
class NativePlayer(context: Context) {
    private val context = context.applicationContext

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
        /** Height of the video track playing, pixels; null while unknown */
        val videoHeight: Int? = null,
        /** Width to height of the picture shown (with the shape of its pixels); null while unknown */
        val aspectRatio: Float? = null,
        /** A frame of the video was shown since it opened */
        val firstFrame: Boolean = false,
    )

    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow<State?>(null)
    /** Null while no video is open */
    val state: StateFlow<State?> = _state.asStateFlow()
    private var video: PageVideo? = null
    private var firstFrame = false
    private val handler = Handler(Looper.getMainLooper())
    /** The player tells no event while it only loads on: the state is told again now and then for the part loaded */
    private val refresh = object : Runnable {
        override fun run() {
            update()
            if (player != null) handler.postDelayed(this, REFRESH_MS)
        }
    }

    /** The ExoPlayer of the open video, for the view that shows it; null while no video is open */
    val exoPlayer: Player? get() = player

    /** The open video was opened with the frame rate left to the app ([open]) */
    var ownFrameRate = false
        private set

    /**
     * Plays [video] from [startPosition] seconds; [cookies]: of the engine, for the address of the video.
     * [ownFrameRate]: the app switches the mode of the display itself ([FrameRateSwitcher]), so the player does
     * not ask the surface for the frame rate (Media3 does it where the switch is seamless)
     */
    fun open(video: PageVideo, cookies: String?, startPosition: Double = 0.0, ownFrameRate: Boolean = false) {
        release()
        this.video = video
        this.ownFrameRate = ownFrameRate
        firstFrame = false
        val headers = ExternalVideoPlayer.headers(video, cookies)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers - USER_AGENT)
        headers[USER_AGENT]?.let { http.setUserAgent(it) }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
        this.player = player
        if (ownFrameRate) player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        player.addListener(listener)
        val item = MediaItem.Builder().setUri(video.url).setMimeType(mimeType(video.kind))
            .setSubtitleConfigurations(subtitleConfigurations(video))
            .build()
        player.setMediaItem(item, (startPosition * 1000).toLong().coerceAtLeast(0))
        player.playWhenReady = true
        player.prepare()
        update()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, REFRESH_MS)
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

    /** The media keys of the remote (and the controls of the picture-in-picture window) */
    fun mediaCommand(command: MediaCommand) {
        when (command) {
            MediaCommand.TOGGLE -> togglePlayback()
            MediaCommand.PLAY -> play()
            MediaCommand.STOP, MediaCommand.PAUSE -> pause()
            MediaCommand.REWIND -> seekBy(-VideoModeController.STEP)
            MediaCommand.FAST_FORWARD -> seekBy(VideoModeController.STEP)
        }
    }

    fun play() {
        player?.play()
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

    /** The video starts again from the beginning when it ends; off for each video [open]ed */
    var looping: Boolean
        get() = player?.repeatMode == Player.REPEAT_MODE_ONE
        set(value) {
            player?.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    /** Stops the playback and frees the decoders; [state] becomes null */
    fun release() {
        handler.removeCallbacks(refresh)
        player?.removeListener(listener)
        player?.release()
        player = null
        video = null
        _state.value = null
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) firstFrame = true
            update()
        }
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
                live = player.isCurrentMediaItemLive,
                buffered = duration?.let { player.bufferedPosition / 1000.0 },
            ),
            buffering = player.playbackState == Player.STATE_BUFFERING,
            ended = ended,
            error = player.playerError,
            tracks = player.currentTracks,
            frameRate = (player as? ExoPlayer)?.videoFormat?.frameRate?.takeIf { it > 0 },
            videoHeight = (player as? ExoPlayer)?.videoFormat?.height?.takeIf { it > 0 },
            aspectRatio = player.videoSize.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width * it.pixelWidthHeightRatio / it.height },
            firstFrame = firstFrame,
        )
    }

    companion object {
        private const val USER_AGENT = "User-Agent"
        private const val REFRESH_MS = 1000L

        /** Streams by their manifests; files by what the server says */
        fun mimeType(kind: PageVideo.Kind): String? = when (kind) {
            PageVideo.Kind.HLS -> MimeTypes.APPLICATION_M3U8
            PageVideo.Kind.DASH, PageVideo.Kind.XML -> MimeTypes.APPLICATION_MPD
            PageVideo.Kind.FILE -> null
        }

        /**
         * The subtitle files of the page ([PageVideo.subtitles], of the type their extension tells), loaded with the
         * headers of the video; the one the page shows now is chosen at the start, the others wait for the menu
         */
        fun subtitleConfigurations(video: PageVideo): List<MediaItem.SubtitleConfiguration> = video.subtitles.map {
            MediaItem.SubtitleConfiguration.Builder(it.url.toUri())
                .setMimeType(it.mimeType)
                .setLanguage(it.language)
                .setLabel(it.label)
                .setSelectionFlags(if (it.showing) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
    }
}
