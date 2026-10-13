package com.phlox.tvwebbrowser.player

import androidx.media3.common.PlaybackException

/**
 * Decides whether a native-player error is worth another attempt and how long to wait between
 * attempts. Kept free of Android framework classes so it can be unit-tested.
 *
 * ExoPlayer already retries transient loads a few times internally (DefaultLoadErrorHandlingPolicy)
 * before surfacing an error, so this is a coarser, second layer that re-prepares the whole source.
 */
object PlaybackRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val BASE_BACKOFF_MS = 2000L

    /**
     * Errors that a fresh prepare() has a realistic chance of surviving: connectivity, timeouts,
     * server-side hiccups and falling off a live window. Decoder, parsing and DRM failures are
     * deterministic for a given stream and are left alone so they fail fast.
     */
    fun isTransient(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true
        else -> false
    }

    /** Exponential backoff for a 1-based [attempt]: BASE, BASE*2, BASE*4, ... */
    fun backoffMs(attempt: Int): Long = BASE_BACKOFF_MS shl (attempt - 1).coerceAtLeast(0)
}
