package com.phlox.tvwebbrowser.webengine.gecko.delegates

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

class MyMediaSessionDelegate: MediaSession.Delegate {
    var mediaSession: MediaSession? = null
    var paused = false
    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        super.onActivated(session, mediaSession)
        this.mediaSession = mediaSession
        this.paused = false
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        super.onDeactivated(session, mediaSession)
        this.mediaSession = null
    }

    override fun onMetadata(
        session: GeckoSession,
        mediaSession: MediaSession,
        meta: MediaSession.Metadata
    ) {
        super.onMetadata(session, mediaSession, meta)
    }

    override fun onFeatures(session: GeckoSession, mediaSession: MediaSession, features: Long) {
        super.onFeatures(session, mediaSession, features)
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        super.onPlay(session, mediaSession)
        paused = false
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        super.onPause(session, mediaSession)
        paused = true
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        super.onStop(session, mediaSession)
    }

    override fun onPositionState(
        session: GeckoSession,
        mediaSession: MediaSession,
        state: MediaSession.PositionState
    ) {
        super.onPositionState(session, mediaSession, state)
    }

    override fun onFullscreen(
        session: GeckoSession,
        mediaSession: MediaSession,
        enabled: Boolean,
        meta: MediaSession.ElementMetadata?
    ) {
        super.onFullscreen(session, mediaSession, enabled, meta)
    }
}