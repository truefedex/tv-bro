package com.phlox.tvwebbrowser.player

import android.media.MediaMetadata
import android.media.session.PlaybackState
import androidx.media3.common.PlaybackException
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerMediaSessionTest {
    private val video = PageVideo("https://media.example.com/film.mp4", PageVideo.Kind.FILE, title = "A film")

    private fun state(playing: Boolean = true, duration: Double? = 120.0, buffering: Boolean = false,
                      ended: Boolean = false, error: PlaybackException? = null) = NativePlayer.State(
        video = video,
        media = MediaState(playing = playing, position = 12.5, duration = duration, fullscreen = true,
            rate = if (playing && !buffering) 1.5 else 0.0, at = 1000L),
        buffering = buffering, ended = ended, error = error)

    private fun PlaybackState.can(action: Long) = actions and action != 0L

    /** The system goes on with the position from when it was told, at the speed of the playback */
    @Test
    fun aPlayingVideoTellsWhereItIsAndHowFastItGoes() {
        val playback = PlayerMediaSession.playbackState(state())
        assertEquals(PlaybackState.STATE_PLAYING, playback.state)
        assertEquals(12500L, playback.position)
        assertEquals(1.5f, playback.playbackSpeed, 0f)
        assertEquals(1000L, playback.lastPositionUpdateTime)
    }

    @Test
    fun aPausedVideoStandsStill() {
        val playback = PlayerMediaSession.playbackState(state(playing = false))
        assertEquals(PlaybackState.STATE_PAUSED, playback.state)
        assertEquals(0f, playback.playbackSpeed, 0f)
    }

    @Test
    fun theStateFollowsTheBufferingTheEndAndAnError() {
        assertEquals(PlaybackState.STATE_BUFFERING, PlayerMediaSession.playbackState(state(buffering = true)).state)
        assertEquals(PlaybackState.STATE_STOPPED, PlayerMediaSession.playbackState(state(playing = false, ended = true)).state)
        val error = PlaybackException("failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(PlaybackState.STATE_ERROR, PlayerMediaSession.playbackState(state(error = error)).state)
    }

    /** The play/pause key of the remote needs these; a live stream has no position to go to */
    @Test
    fun theRemoteCanPlayPauseAndSeekWhereThereIsAnEnd() {
        val file = PlayerMediaSession.playbackState(state())
        assertTrue(file.can(PlaybackState.ACTION_PLAY_PAUSE) && file.can(PlaybackState.ACTION_PLAY) &&
                file.can(PlaybackState.ACTION_PAUSE) && file.can(PlaybackState.ACTION_REWIND) &&
                file.can(PlaybackState.ACTION_FAST_FORWARD) && file.can(PlaybackState.ACTION_SEEK_TO))
        val live = PlayerMediaSession.playbackState(state(duration = null))
        assertTrue(live.can(PlaybackState.ACTION_PLAY_PAUSE))
        assertFalse(live.can(PlaybackState.ACTION_SEEK_TO))
    }

    /** Android TV titles the picture-in-picture window with it */
    @Test
    fun theMetadataHasTheTitleTheServerAndTheDuration() {
        val metadata = PlayerMediaSession.metadata("A film", "media.example.com", 120_000L)
        assertEquals("A film", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("A film", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("media.example.com", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals(120_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        val live = PlayerMediaSession.metadata("A stream", null, null)
        assertFalse(live.containsKey(MediaMetadata.METADATA_KEY_DURATION))
        assertNull(live.getString(MediaMetadata.METADATA_KEY_ARTIST))
    }
}
