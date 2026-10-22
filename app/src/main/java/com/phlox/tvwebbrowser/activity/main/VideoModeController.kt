package com.phlox.tvwebbrowser.activity.main

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import com.phlox.tvwebbrowser.webengine.MediaState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The video mode of the TV: while the page shows a video in fullscreen, the remote controls the video instead
 * of the cursor, which is hidden. Left and right seek (a press by [STEP] seconds at once; held, faster and
 * faster, to where the key goes up), OK plays and pauses, Up and Down give the cursor back for the controls of
 * the page. Back with the cursor ([backCallback]) hides it again; Back in the video mode leaves fullscreen
 * (the fullscreen callback of the activity, below [backCallback]). The seek bar ([Osd]) shows on seeks, on
 * play and pause and while the video is paused, and for a while when the video mode starts or a [hint] comes
 * (longer with a hint). Media keys keep working through the shortcuts.
 */
class VideoModeController(private val host: Host) {
    interface Host {
        fun hideCursor()
        fun showCursor()
        fun togglePlayback()
        fun seekTo(position: Double)
        fun seekBy(delta: Double)
        /** Shows the seek bar, or hides it when null */
        fun renderOsd(osd: Osd?)
        /** [active] changed: the keys go to the video (true) or to the cursor */
        fun onActiveChanged(active: Boolean) {}
        /**
         * Up or Down ([down]): true if the host takes it (the built-in player opens its menu, Down takes the video
         * of the page there); else they give the cursor back
         */
        fun onUpDown(down: Boolean): Boolean = false
    }

    /**
     * What the seek bar shows: the media, and while a seek goes on, where it goes ([seekTarget],
     * [seekDelta] from where it started), in seconds.
     */
    data class Osd(val media: MediaState, val seekTarget: Double? = null, val seekDelta: Double? = null,
                   /** What Down does, drawn after its key cap ("Built-in player") */
                   val hint: String? = null)

    private val handler = Handler(Looper.getMainLooper())
    private var fullscreen = false
    private var media: MediaState? = null
    /** Up or Down gave the cursor back in this fullscreen view */
    private var cursorRequested = false

    /** The keys go to the video */
    var isActive = false
        private set

    /** Shown in the seek bar; a new one shows the bar */
    var hint: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (value != null && isActive) showOsd(HINT_SHOWN_MS) else renderOsd()
        }

    /** Hides the cursor that Up or Down gave back, while there is a video in fullscreen */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            cursorRequested = false
            update()
        }
    }

    // the seek in progress: the key, the position where it started, where it goes, where the page was sent
    // already, whether the key is held
    private var seekKey = 0
    private var seekStart = 0.0
    private var seekTarget: Double? = null
    private var seekSent = 0.0
    private var seekHeld = false
    /** Until when the seek bar shows [seekTarget] rather than the position the page reports */
    private var seekShownUntil = 0L
    /** Until when the seek bar shows while the video plays */
    private var osdUntil = 0L
    /** Keys whose down event went to the video: their up events do too */
    private val swallowedKeys = HashSet<Int>()

    private val updateRunnable = Runnable { renderOsd() }

    fun setFullscreen(fullscreen: Boolean) {
        if (this.fullscreen == fullscreen) return
        this.fullscreen = fullscreen
        cursorRequested = false
        endSeek(apply = false)
        swallowedKeys.clear()
        update()
    }

    fun setMedia(media: MediaState?) {
        val old = this.media
        this.media = media
        if (isActive && old != null && media != null && old.playing != media.playing) {
            //played or paused by OK, by a media key or by the page
            showOsd()
        }
        val target = seekTarget
        if (target != null && !seekHeld && media != null && abs(media.position - target) < SEEK_REACHED_TOLERANCE) {
            //the page reached the seek: the bar follows its position again
            seekShownUntil = 0
        }
        update()
    }

    /** @return true if the event went to the video */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && swallowedKeys.remove(keyCode)) {
            if (keyCode == seekKey) onSeekKeyUp()
            return true
        }
        if (!isActive) return false
        val media = media ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return isVideoKey(keyCode)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                swallowedKeys.add(keyCode)
                onSeekKeyDown(event, media)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                swallowedKeys.add(keyCode)
                if (event.repeatCount == 0) {
                    endSeek(apply = true)
                    host.togglePlayback()
                    showOsd()
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                swallowedKeys.add(keyCode)
                if (event.repeatCount == 0) {
                    endSeek(apply = true)
                    if (!host.onUpDown(keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                        cursorRequested = true
                        update()
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private fun isVideoKey(keyCode: Int) = keyCode in VIDEO_KEYS

    private fun onSeekKeyDown(event: KeyEvent, media: MediaState) {
        val sign = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
        val now = SystemClock.elapsedRealtime()
        if (event.repeatCount == 0 || event.keyCode != seekKey) {
            //a new press: the other direction ends the one held
            endSeek(apply = true)
            seekKey = event.keyCode
            seekStart = currentPosition(media, now)
            seekHeld = false
            val target = inside(media, seekStart + sign * STEP)
            seekTarget = target
            seekSent = seekStart
            send(media, target)
        } else {
            seekHeld = true
            val heldSeconds = (event.eventTime - event.downTime) / 1000.0
            seekTarget = inside(media, seekStart + sign * (STEP + heldDistance(heldSeconds)))
        }
        seekShownUntil = now + SEEK_SHOWN_MS
        showOsd()
    }

    /** Without a duration the position may be unknown: only the distance counts then */
    private fun inside(media: MediaState, position: Double) =
        if (media.duration != null) media.clamp(position) else position

    private fun onSeekKeyUp() {
        endSeek(apply = true)
        showOsd()
    }

    /** The bar shows a seek of a press that went to the page only as far as the page got */
    private fun currentPosition(media: MediaState, now: Long): Double {
        val target = seekTarget
        return if (target != null && now < seekShownUntil) target else media.positionAt(now)
    }

    /**
     * Seeks to [target]: to the position when the page tells it with the duration; else by the distance, from
     * where the page is (the position of pages that give no duration may be unknown)
     */
    private fun send(media: MediaState, target: Double) {
        if (media.duration != null) host.seekTo(target) else host.seekBy(target - seekSent)
        seekSent = target
    }

    /** Ends the seek of a held key, going to its target when [apply] */
    private fun endSeek(apply: Boolean) {
        val target = seekTarget
        val media = media
        if (seekHeld && apply && target != null && media != null) {
            send(media, target)
            seekShownUntil = SystemClock.elapsedRealtime() + SEEK_SHOWN_MS
        }
        seekHeld = false
        seekKey = 0
        if (!apply) {
            seekTarget = null
            seekShownUntil = 0
        }
    }

    /** Shows the seek bar for [duration] ms at least, also while the video plays */
    private fun showOsd(duration: Long = OSD_SHOWN_MS) {
        osdUntil = max(osdUntil, SystemClock.elapsedRealtime() + duration)
        renderOsd()
    }

    /** Follows a change of the fullscreen view, the media or the cursor */
    private fun update() {
        val hasVideo = fullscreen && media?.fullscreen == true
        val active = hasVideo && !cursorRequested
        backCallback.isEnabled = hasVideo && cursorRequested
        if (active != isActive) {
            isActive = active
            if (active) {
                host.hideCursor()
                //with the hint: the user sees at once that Down plays it in the built-in player
                showOsd(if (hint != null) HINT_SHOWN_MS else OSD_SHOWN_MS)
            } else {
                endSeek(apply = true)
                if (fullscreen && cursorRequested) host.showCursor()
            }
            host.onActiveChanged(active)
        }
        renderOsd()
    }

    private fun renderOsd() {
        handler.removeCallbacks(updateRunnable)
        val media = media
        if (!isActive || media == null) {
            host.renderOsd(null)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val target = seekTarget?.takeIf { seekHeld || now < seekShownUntil }
        if (target == null && !seekHeld) seekTarget = null
        if (media.playing && target == null && now >= osdUntil) {
            host.renderOsd(null)
            return
        }
        host.renderOsd(Osd(media, target, target?.let { it - seekStart }, hint))
        //the next change: the end of the seek shown or of the bar while it plays
        val next = listOf(seekShownUntil, osdUntil).filter { it > now }.minOrNull() ?: return
        handler.postDelayed(updateRunnable, next - now)
    }

    companion object {
        /** Seconds of a press of Left or Right */
        const val STEP = 10.0
        private const val OSD_SHOWN_MS = 3000L
        /** The seek bar with a new hint, as when the video goes fullscreen with a stream the built-in player can play */
        const val HINT_SHOWN_MS = 5000L
        private const val SEEK_SHOWN_MS = 2000L
        /** Seconds: a position this near to the target means the page got there */
        private const val SEEK_REACHED_TOLERANCE = 1.5
        private val VIDEO_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

        /**
         * Seconds a held key seeks beyond the first [STEP], [heldSeconds] after it went down (the repeat of the
         * key starts after about half a second): 20 seconds per second, then 60 after 2 s, 180 after 5 s.
         */
        fun heldDistance(heldSeconds: Double): Double {
            val t = max(0.0, heldSeconds - 0.5)
            return 20 * min(t, 2.0) + 60 * (min(t, 5.0) - 2).coerceAtLeast(0.0) + 180 * (t - 5).coerceAtLeast(0.0)
        }
    }
}
