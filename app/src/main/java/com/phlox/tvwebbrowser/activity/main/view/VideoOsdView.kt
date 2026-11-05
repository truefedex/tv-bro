package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.VideoModeController
import com.phlox.tvwebbrowser.databinding.ViewVideoOsdBinding
import com.phlox.tvwebbrowser.utils.themeColor
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The seek bar of a video ([VideoModeController.Osd]), the same for the player of the page (the video mode) and the
 * built-in player: across the bottom of the screen, under a gradient, the title and the caption ([setInfo]), then the
 * round glyph of what OK does (play while paused, pause while it plays, the direction of a seek), the position, the
 * progress (played, loaded, the thumb) and the duration, and at the end what Up and Down do. While a seek goes, the
 * position the video is at is dimmed and a bubble over the thumb tells where the seek goes and how far. A live stream
 * shows its badge and the distance of a seek instead of the timeline; an unknown duration only the distance. While
 * the video plays the position goes on by itself.
 */
class VideoOsdView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val vb = ViewVideoOsdBinding.inflate(LayoutInflater.from(context), this)
    private var osd: VideoModeController.Osd? = null
    private val hintGap = resources.getDimensionPixelSize(R.dimen.video_osd_hint_gap)
    private val bubbleGap = resources.getDimensionPixelSize(R.dimen.video_osd_bubble_gap)
    private val ink = context.themeColor(R.attr.tbColorDsInk)
    private val inkFaint = context.themeColor(R.attr.tbColorDsInkFaint)

    private val tick = object : Runnable {
        override fun run() {
            bind()
            if (osd?.let { it.media.playing && it.seekTarget == null } == true) postDelayed(this, TICK_MS)
        }
    }

    init {
        setBackgroundResource(R.drawable.ds_video_scrim)
        clipChildren = false
        vb.tvTitle.isVisible = false
        vb.tvCaption.isVisible = false
    }

    /** The title (of the tab) and the caption (which player, the height of the video, the kind, the site) */
    fun setInfo(title: String?, caption: String?) {
        vb.tvTitle.text = title
        vb.tvTitle.isVisible = !title.isNullOrBlank()
        vb.tvCaption.text = caption
        vb.tvCaption.isVisible = !caption.isNullOrBlank()
    }

    /** Shows [osd], or hides the bar when null */
    fun render(osd: VideoModeController.Osd?) {
        this.osd = osd
        removeCallbacks(tick)
        if (osd == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        tick.run()
    }

    private fun bind() {
        val osd = osd ?: return
        val media = osd.media
        val duration = media.duration
        vb.llHint.isVisible = osd.hint != null || osd.upHint != null
        vb.llUpHint.isVisible = osd.upHint != null
        vb.tvUpHint.text = osd.upHint
        vb.llDownHint.isVisible = osd.hint != null
        vb.tvHint.text = osd.hint
        //the gap between the two hints, only when both show
        vb.llDownHint.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = if (osd.upHint != null) hintGap else 0 }
        val target = osd.seekTarget
        val delta = osd.seekDelta?.takeIf { abs(it) >= 0.5 }
        vb.ivState.setImageResource(when {
            target != null && delta != null -> if (delta < 0) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward
            media.playing -> R.drawable.ic_pause
            else -> R.drawable.ic_play
        })
        vb.llLive.isVisible = media.live
        val timeline = duration != null
        vb.tvPosition.isVisible = timeline
        vb.vProgress.isVisible = timeline
        vb.tvDuration.isVisible = timeline
        //without a timeline the distance of a seek stands in the row; with one, in the bubble over the thumb
        vb.tvDelta.isVisible = !timeline && delta != null
        if (delta != null) vb.tvDelta.text = signed(delta)
        if (duration == null) {
            vb.llBubble.isVisible = false
            return
        }
        val position = media.positionAt(SystemClock.elapsedRealtime())
        vb.tvPosition.text = format(position)
        vb.tvPosition.setTextColor(if (target != null) inkFaint else ink)
        vb.tvDuration.text = format(duration)
        vb.vProgress.setProgress((position / duration).toFloat(), media.buffered?.let { (it / duration).toFloat() },
            target?.let { (it / duration).toFloat() }, media.playing)
        vb.llBubble.isVisible = target != null
        if (target != null) {
            vb.tvBubbleTime.text = format(target)
            vb.tvBubbleDelta.text = signed(delta ?: 0.0)
            placeBubble()
        }
    }

    /** The bubble goes over the thumb, inside the margins of the bar */
    private fun placeBubble() {
        val progress = vb.vProgress
        if (progress.width == 0 || width == 0) {
            post { if (vb.llBubble.isVisible) placeBubble() }
            return
        }
        var left = progress.left.toFloat()
        var top = progress.top.toFloat()
        var parent = progress.parent as View
        while (parent !== this) {
            left += parent.left
            top += parent.top
            parent = parent.parent as View
        }
        val bubble = vb.llBubble
        bubble.measure(MeasureSpec.makeMeasureSpec(bubble.layoutParams.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val bubbleWidth = bubble.measuredWidth
        val margin = vb.llContent.paddingLeft.toFloat()
        bubble.translationX = (left + progress.thumbX() - bubbleWidth / 2f)
            .coerceIn(margin, (width - margin - bubbleWidth).coerceAtLeast(margin))
        bubble.translationY = top + progress.lineTop() - bubbleGap - bubble.measuredHeight
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && vb.llBubble.isVisible) placeBubble()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TICK_MS = 250L

        /** In whole seconds counted down, as the players of sites tell them (the same numbers after a handoff) */
        private fun format(seconds: Double): String = DateUtils.formatElapsedTime(seconds.toLong())

        /** "+2:30", "−0:30", "+1:02:00" (with the minus sign; the minutes or hours without a leading zero) */
        private fun signed(seconds: Double): String {
            val total = abs(seconds).roundToLong()
            val h = total / 3600
            val m = total / 60 % 60
            val s = total % 60
            val text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
            return (if (seconds < 0) "−" else "+") + text
        }
    }
}
