package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.PageVideoHintController
import com.phlox.tvwebbrowser.databinding.ViewPageVideoHintBinding

/**
 * The plate of the hint about a found video that plays in the page ([PageVideoHintController]): it fades in
 * and out, and takes no focus and no keys (Back goes to the callback of the controller).
 */
class PageVideoHintView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    init {
        ViewPageVideoHintBinding.inflate(LayoutInflater.from(context), this)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.ds_toast_bg)
        setPaddingRelative(resources.getDimensionPixelSize(R.dimen.page_video_hint_padding_start), 0,
            resources.getDimensionPixelSize(R.dimen.page_video_hint_padding_end), 0)
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = GONE
    }

    fun appear() {
        animate().cancel()
        if (visibility != VISIBLE) alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(FADE_IN_MS).start()
    }

    /** Fades out, or goes at once (the bar or a panel comes over its place) */
    fun disappear(fade: Boolean) {
        if (visibility != VISIBLE) return
        animate().cancel()
        if (!fade) {
            visibility = View.GONE
            return
        }
        animate().alpha(0f).setDuration(PageVideoHintController.FADE_MS).withEndAction { visibility = View.GONE }.start()
    }

    companion object {
        private const val FADE_IN_MS = 300L
    }
}
