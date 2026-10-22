package com.phlox.tvwebbrowser.webengine

import org.json.JSONObject

/**
 * The frames of a page that have media, as their scripts report them, and the frame that gets a media
 * key: the one that shows a video in fullscreen, else the one that started playing last, else the one
 * whose media changed last. [F] is how a frame is reached (compared by identity).
 */
class MediaFrames<F : Any> {
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

/** The commands of media_controls.js (see tvBroWatchMedia) */
enum class MediaCommand(val js: String) {
    TOGGLE("toggle"), PAUSE("pause"), STOP("stop"), REWIND("rewind"), FAST_FORWARD("forward");

    companion object {
        fun seekTo(position: Double) = "seekTo:$position"
        fun seekBy(delta: Double) = "seekBy:$delta"
    }
}

/** The reports of media_controls.js, the script both engines run in every frame */
object MediaReports {
    /** A report, received at [now] (elapsedRealtime); null: the frame has no media */
    fun parse(json: JSONObject, now: Long): MediaState? {
        if (!json.optBoolean("media")) return null
        val duration = json.optDouble("duration")
        return MediaState(
            playing = json.optBoolean("playing"),
            position = json.optDouble("position", 0.0).takeIf { it.isFinite() } ?: 0.0,
            duration = duration.takeIf { it.isFinite() && it > 0 },
            fullscreen = json.optBoolean("fullscreen"),
            rate = json.optDouble("rate", 1.0).takeIf { it.isFinite() } ?: 1.0,
            muted = json.optBoolean("muted"),
            at = now,
        )
    }

    /** Whether the [json] message of a frame is a media report (the frame also reports its videos) */
    fun isReport(json: JSONObject): Boolean = json.has("media")

    /** What the media of a report plays; null: the frame has no media */
    fun source(json: JSONObject): MediaSource? {
        if (!json.optBoolean("media")) return null
        val src = if (json.isNull("src")) null else json.optString("src").takeIf { it.startsWith("http") }
        return MediaSource(src, json.optBoolean("mse"))
    }
}
