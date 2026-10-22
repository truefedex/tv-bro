package com.phlox.tvwebbrowser.activity.main

import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo

/**
 * When the hint plate of the TV tells that the built-in player can play the video the page plays. It shows
 * after a found video of the page ([setMediaVideo], [com.phlox.tvwebbrowser.webengine.PageVideosSupport.mediaVideo])
 * has played with sound for [STEADY_MS] in the page's own player, while nothing covers the page ([setBlocked]):
 * not in fullscreen (the seek bar of the video mode has its own hint), not for muted or short videos (previews,
 * ads), at most once per page ([setPage]) and per video (pages that rewrite their address while it plays), and only while the [Host] allows hints (the setting, the user has not
 * used the built-in player a few times yet). It hides after [SHOWN_MS], or at once when the page or the tab
 * changes, something covers the page, the page goes fullscreen or the video is no longer known. Back while it
 * shows ([backCallback], also while it fades out after its time) opens the Videos panel at the video.
 */
class PageVideoHintController(private val host: Host) {
    interface Host {
        /** Hints are on and the user has not learned the built-in player yet */
        val hintsAllowed: Boolean
        fun showHint()
        /** The plate fades out (in [FADE_MS]) after its time, else it goes at once */
        fun hideHint(fade: Boolean)
        /** Back on the plate: the Videos panel with the focus on the [video] */
        fun openVideos(video: PageVideo)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var page: String? = null
    /** The pages the hint was shown for */
    private val shownPages = HashSet<String>()
    /** The addresses of the videos the hint was shown for */
    private val shownVideos = HashSet<String>()
    private var media: MediaState? = null
    private var video: PageVideo? = null
    private var blocked = true
    /** The rules held since the last check: the hint shows when they still hold [STEADY_MS] later */
    private var waiting = false

    /** The plate shows (not while it fades out) */
    var isShown = false
        private set

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val video = video
            hide(fade = false)
            if (video != null) host.openVideos(video)
        }
    }

    private val showRunnable = Runnable {
        waiting = false
        if (canShow()) show()
    }
    private val hideRunnable = Runnable { hide(fade = true) }
    private val fadedRunnable = Runnable { backCallback.isEnabled = false }

    /** The page the tab shows (the tab and its address without the fragment); null: none */
    fun setPage(page: String?) {
        if (this.page == page) return
        this.page = page
        hide(fade = false)
        update()
    }

    fun setMedia(media: MediaState?) {
        this.media = media
        update()
    }

    fun setMediaVideo(video: PageVideo?) {
        this.video = video
        update()
    }

    /** Something covers the page: the bar, a panel or screen, a menu, the player, a fullscreen view */
    fun setBlocked(blocked: Boolean) {
        if (this.blocked == blocked) return
        this.blocked = blocked
        update()
    }

    private fun update() {
        if (isShown) {
            if (blocked || video == null || media?.fullscreen == true) hide(fade = false)
            return
        }
        if (!canShow()) {
            waiting = false
            handler.removeCallbacks(showRunnable)
        } else if (!waiting) {
            waiting = true
            handler.postDelayed(showRunnable, STEADY_MS)
        }
    }

    private fun canShow(): Boolean {
        val page = page ?: return false
        val media = media ?: return false
        val video = video ?: return false
        if (blocked || page in shownPages || video.url in shownVideos || !media.playing || media.muted || media.fullscreen) return false
        val duration = media.duration ?: video.duration
        //live streams have no duration
        if (duration != null && duration < MIN_DURATION) return false
        return host.hintsAllowed
    }

    private fun show() {
        shownPages += page ?: return
        shownVideos += video?.url ?: return
        isShown = true
        handler.removeCallbacks(fadedRunnable)
        backCallback.isEnabled = true
        host.showHint()
        handler.postDelayed(hideRunnable, SHOWN_MS)
    }

    private fun hide(fade: Boolean) {
        handler.removeCallbacks(showRunnable)
        waiting = false
        if (!isShown) {
            if (!fade) backCallback.isEnabled = false
            return
        }
        isShown = false
        handler.removeCallbacks(hideRunnable)
        host.hideHint(fade)
        if (fade) {
            //Back pressed while it fades out still opens the panel
            handler.postDelayed(fadedRunnable, FADE_MS)
        } else {
            handler.removeCallbacks(fadedRunnable)
            backCallback.isEnabled = false
        }
    }

    companion object {
        /** How long the video plays with sound before the hint shows */
        const val STEADY_MS = 3000L
        const val SHOWN_MS = 7000L
        const val FADE_MS = 400L
        /** Seconds: shorter videos are previews and ads */
        const val MIN_DURATION = 60.0
        /** After the user opened videos in the built-in player this many times, there are no hints */
        const val MAX_PLAYER_OPENS = 3
    }
}
