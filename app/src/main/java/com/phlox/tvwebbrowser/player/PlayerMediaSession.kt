package com.phlox.tvwebbrowser.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import com.phlox.tvwebbrowser.webengine.MediaCommand

/**
 * The media session of the built-in player while it plays in the picture-in-picture window (a platform session,
 * active only while the window is there): Android TV takes the title of the window from it (its notification of
 * Android 8 to 12 says "No title program" without one), and the media keys of the remote reach the player through it
 * while the window floats over another app (over the browser, the browser gets them first: `MainActivity.commands`).
 * Main thread only; [release] it when the window goes.
 */
class PlayerMediaSession(context: Context) {
    private val session = MediaSession(context.applicationContext, TAG)
    private var player: NativePlayer? = null
    /** The title, the server and the duration the session tells, set again only when one of them changes */
    private var shownMetadata: Triple<String, String?, Long?>? = null

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            val player = player ?: return
            //from the end or after an error, as OK does: it starts again
            if (player.state.value?.media?.playing != true) player.togglePlayback()
        }

        override fun onPause() {
            player?.pause()
        }

        override fun onStop() {
            player?.pause()
        }

        override fun onSeekTo(pos: Long) {
            player?.seekTo(pos / 1000.0)
        }

        override fun onRewind() {
            player?.mediaCommand(MediaCommand.REWIND)
        }

        override fun onFastForward() {
            player?.mediaCommand(MediaCommand.FAST_FORWARD)
        }
    }

    init {
        session.setCallback(callback)
    }

    /** The session tells and controls [player] from now on */
    fun show(player: NativePlayer) {
        this.player = player
        shownMetadata = null
        update(player.state.value)
        session.isActive = true
    }

    /** The state of the player shown changed */
    fun update(state: NativePlayer.State?) {
        if (state == null) return
        val metadata = Triple(ExternalVideoPlayer.title(state.video), Uri.parse(state.video.url).host,
            state.media.duration?.let { (it * 1000).toLong() })
        if (metadata != shownMetadata) {
            shownMetadata = metadata
            session.setMetadata(metadata(metadata.first, metadata.second, metadata.third))
        }
        session.setPlaybackState(playbackState(state))
    }

    fun release() {
        player = null
        session.isActive = false
        session.release()
    }

    companion object {
        private const val TAG = "TVBroPictureInPicture"

        /** What the player does now, for the remote and the system (the position goes on from [MediaState.at]) */
        fun playbackState(state: NativePlayer.State): PlaybackState {
            val media = state.media
            val code = when {
                state.error != null -> PlaybackState.STATE_ERROR
                state.ended -> PlaybackState.STATE_STOPPED
                media.playing && state.buffering -> PlaybackState.STATE_BUFFERING
                media.playing -> PlaybackState.STATE_PLAYING
                else -> PlaybackState.STATE_PAUSED
            }
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
            //a live stream has no position to go to
            if (media.duration != null) actions = actions or PlaybackState.ACTION_SEEK_TO
            val speed = if (code == PlaybackState.STATE_PLAYING) media.rate.toFloat() else 0f
            return PlaybackState.Builder()
                .setActions(actions)
                .setState(code, (media.position * 1000).toLong(), speed, media.at)
                .build()
        }

        /** The title of the video, the server under it and the duration, milliseconds (null for a live stream) */
        fun metadata(title: String, server: String?, duration: Long?): MediaMetadata =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .apply {
                    if (server != null) {
                        putString(MediaMetadata.METADATA_KEY_ARTIST, server)
                        putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, server)
                    }
                    if (duration != null) putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                }
                .build()
    }
}
