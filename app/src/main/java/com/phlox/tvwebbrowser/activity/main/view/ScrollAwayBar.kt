package com.phlox.tvwebbrowser.activity.main.view

import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.RelativeLayout

/**
 * Slides the [bar] away while the page scrolls down, and back when it scrolls up or reaches its top.
 * The [page] (laid out above the bar in a RelativeLayout) takes the room of the hidden bar: it grows when
 * the bar starts sliding out, and shrinks when the bar has slid back in over it. Growing moves the scroll
 * position at the end of a page, so scroll events are ignored for a moment after each change, and the bar
 * moves only after some scrolling in one direction.
 */
class ScrollAwayBar(private val bar: View, private val page: View) {
    var hidden = false
        private set
    /** Scrolled since the last change of the direction */
    private var scrolled = 0
    private var ignoreScrollUntil = 0L
    private val threshold = (THRESHOLD_DP * bar.resources.displayMetrics.density).toInt()

    /** The page scrolled to [scrollY] by [dy] (in pixels, positive downwards) */
    fun onPageScrolled(scrollY: Int, dy: Int) {
        if (scrollY <= 0) {
            show()
            return
        }
        if (dy == 0 || SystemClock.uptimeMillis() < ignoreScrollUntil) return
        if ((dy > 0) != (scrolled > 0)) {
            scrolled = 0
        }
        scrolled += dy
        if (scrolled >= threshold) {
            hide()
        } else if (scrolled <= -threshold) {
            show()
        }
    }

    fun hide() {
        if (hidden) return
        hidden = true
        scrolled = 0
        ignoreScrollForAWhile()
        bar.animate().cancel()
        setPageUnderBar(true)
        bar.animate().translationY(bar.height.toFloat()).setDuration(ANIMATION_DURATION)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun show(animate: Boolean = true) {
        if (!hidden) return
        hidden = false
        scrolled = 0
        ignoreScrollForAWhile()
        bar.animate().cancel()
        if (animate) {
            bar.animate().translationY(0f).setDuration(ANIMATION_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { if (!hidden) setPageUnderBar(false) }.start()
        } else {
            bar.translationY = 0f
            setPageUnderBar(false)
        }
    }

    private fun setPageUnderBar(under: Boolean) {
        val params = page.layoutParams as RelativeLayout.LayoutParams
        if (under) {
            params.removeRule(RelativeLayout.ABOVE)
        } else {
            params.addRule(RelativeLayout.ABOVE, bar.id)
        }
        page.layoutParams = params
    }

    private fun ignoreScrollForAWhile() {
        ignoreScrollUntil = SystemClock.uptimeMillis() + ANIMATION_DURATION + QUIET_TIME
    }

    companion object {
        private const val THRESHOLD_DP = 32
        private const val ANIMATION_DURATION = 200L
        /** For the page to be laid out with its new size and report the scroll position it moved to */
        private const val QUIET_TIME = 300L
    }
}
