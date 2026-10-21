package com.phlox.tvwebbrowser.webengine.webview

/**
 * The frames of a page that have media, as their scripts report them, and the frame that gets a media
 * key: the one that shows a video in fullscreen, else the one that started playing last, else the one
 * whose media changed last. [F] is how a frame is reached (compared by identity).
 */
internal class MediaFrames<F : Any> {
    private class State(val playing: Boolean, val fullscreen: Boolean, val playingSince: Long, val changedAt: Long)

    private val frames = HashMap<F, State>()
    private var clock = 0L

    fun onState(frame: F, media: Boolean, playing: Boolean, fullscreen: Boolean) {
        if (!media) {
            frames.remove(frame)
            return
        }
        val now = ++clock
        val old = frames[frame]
        val playingSince = if (!playing) 0 else if (old?.playing == true) old.playingSince else now
        frames[frame] = State(playing, fullscreen, playingSince, now)
    }

    /** The main frame went to another page: the frames of the old one are gone */
    fun clear() = frames.clear()

    fun target(): F? = frames.entries.filter { it.value.fullscreen }.maxByOrNull { it.value.changedAt }?.key
        ?: frames.entries.filter { it.value.playing }.maxByOrNull { it.value.playingSince }?.key
        ?: frames.entries.maxByOrNull { it.value.changedAt }?.key
}
