package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.os.SystemClock
import com.phlox.tvwebbrowser.webengine.MediaState
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * The media session of the page (GeckoView's: the media of all frames of the tab) and its state for
 * the video mode, reported to [onStateChanged] (null while there is no active session).
 */
class MyMediaSessionDelegate(private val onStateChanged: (MediaState?) -> Unit = {}) : MediaSession.Delegate {
    var mediaSession: MediaSession? = null
    var paused = false
    private var position = 0.0
    private var positionAt = 0L
    private var duration: Double? = null
    private var rate = 1.0
    /** GeckoView tells it only when the media element itself goes fullscreen */
    private var fullscreen = false

    /**
     * The page shows an element in fullscreen. Players mostly put their box with the video and their controls
     * there, not the video: the media of the session counts as fullscreen then too.
     */
    var pageFullscreen = false
        set(value) {
            if (field == value) return
            field = value
            report()
        }

    /** The position now, going on from the last report while it plays */
    val currentPosition: Double?
        get() = state()?.positionAt(SystemClock.elapsedRealtime())

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        super.onActivated(session, mediaSession)
        this.mediaSession = mediaSession
        this.paused = false
        position = 0.0
        positionAt = SystemClock.elapsedRealtime()
        duration = null
        rate = 1.0
        report()
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        super.onDeactivated(session, mediaSession)
        this.mediaSession = null
        fullscreen = false
        report()
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        super.onPlay(session, mediaSession)
        movePosition()
        paused = false
        report()
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        super.onPause(session, mediaSession)
        movePosition()
        paused = true
        report()
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        super.onStop(session, mediaSession)
        movePosition()
        paused = true
        report()
    }

    override fun onPositionState(
        session: GeckoSession,
        mediaSession: MediaSession,
        state: MediaSession.PositionState
    ) {
        super.onPositionState(session, mediaSession, state)
        position = state.position.takeIf { it.isFinite() } ?: 0.0
        positionAt = SystemClock.elapsedRealtime()
        duration = state.duration.takeIf { it.isFinite() && it > 0 }
        rate = state.playbackRate.takeIf { it.isFinite() } ?: 1.0
        report()
    }

    override fun onFullscreen(
        session: GeckoSession,
        mediaSession: MediaSession,
        enabled: Boolean,
        meta: MediaSession.ElementMetadata?
    ) {
        super.onFullscreen(session, mediaSession, enabled, meta)
        fullscreen = enabled
        meta?.duration?.takeIf { it.isFinite() && it > 0 }?.let { if (duration == null) duration = it }
        report()
    }

    /** The position reached while it played, before it stops or starts */
    private fun movePosition() {
        val now = SystemClock.elapsedRealtime()
        state()?.let { position = it.positionAt(now) }
        positionAt = now
    }

    private fun state(): MediaState? {
        if (mediaSession == null) return null
        return MediaState(playing = !paused, position = position, duration = duration, fullscreen = fullscreen || pageFullscreen,
            rate = rate, at = positionAt)
    }

    private fun report() = onStateChanged(state())
}
