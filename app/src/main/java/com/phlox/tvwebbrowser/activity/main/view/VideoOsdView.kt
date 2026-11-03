package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.VideoModeController
import com.phlox.tvwebbrowser.databinding.ViewVideoOsdBinding
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The seek bar of the video mode ([VideoModeController.Osd]): play or pause, the position (where a seek goes,
 * with its distance), the progress and the duration, and under them what Up and Down do. While the video plays
 * the position goes on by itself.
 * A video without a duration (a live stream, or a page that tells no position) shows only the distance of a seek.
 */
class VideoOsdView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private val vb = ViewVideoOsdBinding.inflate(LayoutInflater.from(context), this)
    private var osd: VideoModeController.Osd? = null
    private val hintGap = resources.getDimensionPixelSize(R.dimen.ds_space_gutter)

    private val tick = object : Runnable {
        override fun run() {
            bind()
            if (osd?.let { it.media.playing && it.seekTarget == null } == true) postDelayed(this, TICK_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.ds_toast_bg)
        val padding = resources.getDimensionPixelSize(R.dimen.video_osd_padding)
        setPadding(padding, padding, padding, padding)
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
        vb.ivState.setImageResource(if (media.playing) R.drawable.ic_play else R.drawable.ic_pause)
        val delta = osd.seekDelta
        vb.tvDelta.isVisible = delta != null && abs(delta) >= 0.5
        if (delta != null) vb.tvDelta.text = (if (delta < 0) "−" else "+") + format(abs(delta))
        vb.tvPosition.isVisible = duration != null
        vb.vProgress.isVisible = duration != null
        vb.tvDuration.isVisible = duration != null
        if (duration == null) return
        val position = osd.seekTarget ?: media.positionAt(SystemClock.elapsedRealtime())
        vb.tvPosition.text = format(position)
        vb.tvDuration.text = format(duration)
        vb.vProgress.setProgress((position / duration).toFloat(),
            if (osd.seekTarget != null) (media.positionAt(SystemClock.elapsedRealtime()) / duration).toFloat() else null)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TICK_MS = 250L

        private fun format(seconds: Double): String = DateUtils.formatElapsedTime(seconds.roundToLong())
    }
}
