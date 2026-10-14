package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * Tracks Gecko's active media session so the media transport shortcuts (play/pause, stop,
 * rewind, fast forward) can drive the page's own player.
 *
 * Only transport control lives here. Nothing hands a stream over to another player: the app
 * has no reliable way of telling which video on a page is "the" video, so that choice is left
 * to the user through the stream list.
 */
class MyMediaSessionDelegate : MediaSession.Delegate {
    var mediaSession: MediaSession? = null
        private set
    var paused = true
        private set

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.i(TAG, "media-activated")
        this.mediaSession = mediaSession
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.i(TAG, "media-deactivated")
        if (this.mediaSession === mediaSession) this.mediaSession = null
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        Log.i(TAG, "media-playing")
        paused = false
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        Log.i(TAG, "media-paused")
        paused = true
    }

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
        Log.i(TAG, "media-stopped")
        paused = true
    }

    /** Called by the progress delegate when a navigation starts. */
    fun onNavigation() {
        paused = true
    }

    companion object {
        val TAG: String = MyMediaSessionDelegate::class.java.simpleName
    }
}
