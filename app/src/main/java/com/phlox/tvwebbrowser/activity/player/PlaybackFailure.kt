package com.phlox.tvwebbrowser.activity.player

/**
 * Set by a player activity when it gives up on a stream, so that the browser can
 * move on to the next candidate once it is resumed. A player activity cannot
 * report back directly because it is started with startActivity().
 */
object PlaybackFailure {
    @Volatile
    var last: String? = null
}
