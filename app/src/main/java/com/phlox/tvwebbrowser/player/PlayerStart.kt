package com.phlox.tvwebbrowser.player

/**
 * Whether the built-in player started a video that the rules gave it from the page by themselves
 * ([com.phlox.tvwebbrowser.activity.main.PlayerChoice]): it did not when it showed no frame within [TIMEOUT_MS] of
 * opening, or met an error before its first frame. The video then goes back to the player of the page. A video the
 * user opened is not watched: its error shows in the player.
 * No views and no clock of its own: the time comes from [now].
 */
class PlayerStart(private val now: () -> Long) {
    private var watching = false
    private var openedAt = 0L

    /** A video opened; [watch]: it goes back to the page if it does not start */
    fun open(watch: Boolean) {
        watching = watch
        openedAt = now()
    }

    /** The player is gone, or took a video that plays already (back from the picture-in-picture window) */
    fun stop() {
        watching = false
    }

    /**
     * The state of the player changed, or [remaining] passed. @return true when the video did not start: give it
     * back to the page (once)
     */
    fun check(firstFrame: Boolean, error: Boolean): Boolean {
        if (!watching) return false
        if (firstFrame) {
            watching = false
            return false
        }
        if (!error && now() - openedAt < TIMEOUT_MS) return false
        watching = false
        return true
    }

    /** Milliseconds until the start times out; null when nothing is watched */
    fun remaining(): Long? = if (watching) (openedAt + TIMEOUT_MS - now()).coerceAtLeast(0) else null

    companion object {
        /** A video the rules opened shows its first frame within this time, or goes back to the page */
        const val TIMEOUT_MS = 5000L
    }
}
