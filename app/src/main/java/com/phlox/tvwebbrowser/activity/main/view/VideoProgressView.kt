package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.themeColor

/**
 * The progress line of the seek bar of the video mode: the part played in the accent color and a thumb
 * where the video is, or where a seek goes (then a mark stays where the video is).
 */
class VideoProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private var progress = 0f
    private var playedMark: Float? = null
    private val onToast = context.themeColor(R.attr.tbColorDsOnToast)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(onToast, TRACK_ALPHA)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.attr.tbColorDsAccent) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onToast }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(onToast, MARK_ALPHA)
    }
    private val lineHeight = resources.getDimension(R.dimen.video_osd_line_height)
    private val thumbRadius = resources.getDimension(R.dimen.video_osd_thumb_radius)
    private val rect = RectF()

    /** [progress] and [playedMark] (where the video is while a seek goes elsewhere) are parts of the duration */
    fun setProgress(progress: Float, playedMark: Float?) {
        val p = progress.coerceIn(0f, 1f)
        val m = playedMark?.coerceIn(0f, 1f)
        if (p == this.progress && m == this.playedMark) return
        this.progress = p
        this.playedMark = m
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        if (right <= left) return
        val cy = height / 2f
        val radius = lineHeight / 2
        rect.set(left, cy - radius, right, cy + radius)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val x = left + (right - left) * progress
        rect.set(left, cy - radius, x, cy + radius)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        playedMark?.let { mark ->
            val mx = left + (right - left) * mark
            canvas.drawCircle(mx, cy, thumbRadius / 2, markPaint)
        }
        canvas.drawCircle(x, cy, thumbRadius, thumbPaint)
    }

    companion object {
        private const val TRACK_ALPHA = 0x4D
        private const val MARK_ALPHA = 0xB3
    }
}
