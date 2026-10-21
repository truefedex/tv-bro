package com.phlox.tvwebbrowser.webengine.webview

import com.phlox.tvwebbrowser.webengine.MediaState

/**
 * The frames of a page that have media, as their scripts report them, and the frame that gets a media
 * key: the one that shows a video in fullscreen, else the one that started playing last, else the one
 * whose media changed last. [F] is how a frame is reached (compared by identity).
 */
internal class MediaFrames<F : Any> {
    private class Frame(val media: MediaState, val playingSince: Long, val changedAt: Long)

    private val frames = HashMap<F, Frame>()
    private var clock = 0L

    /** The media of [frame] the commands go to; null: it has none */
    fun onState(frame: F, media: MediaState?) {
        if (media == null) {
            frames.remove(frame)
            return
        }
        val now = ++clock
        val old = frames[frame]
        val playingSince = if (!media.playing) 0 else if (old?.media?.playing == true) old.playingSince else now
        frames[frame] = Frame(media, playingSince, now)
    }

    /** The main frame went to another page: the frames of the old one are gone */
    fun clear() = frames.clear()

    fun target(): F? = targetEntry()?.key

    /** The media of [target] */
    fun targetMedia(): MediaState? = targetEntry()?.value?.media

    private fun targetEntry(): Map.Entry<F, Frame>? =
        frames.entries.filter { it.value.media.fullscreen }.maxByOrNull { it.value.changedAt }
            ?: frames.entries.filter { it.value.media.playing }.maxByOrNull { it.value.playingSince }
            ?: frames.entries.maxByOrNull { it.value.changedAt }
}
