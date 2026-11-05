package com.phlox.tvwebbrowser.webengine

import kotlinx.coroutines.flow.StateFlow

/**
 * A [WebEngine] that tells the state of the media of its page that the media keys control (the video
 * of the fullscreen view, else the one that plays) and seeks in it. The video mode of the TV uses it.
 */
interface MediaPlaybackSupport {
    /** Null while the page has no media the engine can control; the same flow for the engine's whole life */
    val mediaState: StateFlow<MediaState?>
    /** Seeks to [position] seconds */
    fun seekTo(position: Double)
    /** Seeks by [delta] seconds (back when negative) */
    fun seekBy(delta: Double)
    /** Pauses the media (the built-in player plays it instead) */
    fun pausePlayback()
    /** Plays the media (the built-in player gives it back) */
    fun resumePlayback()
}

data class MediaState(
    val playing: Boolean,
    /** Seconds, at [at] */
    val position: Double,
    /** Seconds; null when unknown or endless (a live stream) */
    val duration: Double?,
    /** The media is in the fullscreen view of the page */
    val fullscreen: Boolean,
    val rate: Double = 1.0,
    /** It plays without sound (muted, or at volume 0) */
    val muted: Boolean = false,
    /** When [position] was reported, in [android.os.SystemClock.elapsedRealtime] milliseconds */
    val at: Long,
    /** A live stream: it has no end (and no [duration]) */
    val live: Boolean = false,
    /** Protected (DRM, Encrypted Media Extensions): only the page can play it */
    val drm: Boolean = false,
    /** What it plays: its address (`blob:` for a stream of the page); another one is another video. Null if unknown */
    val source: String? = null,
    /** Seconds from the start it has loaded, as far as it plays on without waiting; null if unknown (pages) */
    val buffered: Double? = null,
) {
    /** The position at [now] (elapsedRealtime), going on while it plays */
    fun positionAt(now: Long): Double {
        val position = if (playing) position + (now - at).coerceAtLeast(0) / 1000.0 * rate else position
        return clamp(position)
    }

    /** [position] inside the media */
    fun clamp(position: Double): Double {
        val max = duration ?: Double.MAX_VALUE
        return position.coerceIn(0.0, max)
    }
}

/** Null if the engine can not control the media of pages */
val WebEngine.mediaPlayback: MediaPlaybackSupport?
    get() = this as? MediaPlaybackSupport
