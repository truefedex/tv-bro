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
 * The progress of the seek bar of a video: the line, the part loaded, the part played in the selected color and the
 * thumb where the video is (smaller while it plays). While a seek goes, the thumb (bigger, with the focus ring) is
 * where it goes, the line between where the video is and there is lighter, and a mark stays where the video is.
 */
class VideoProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private var position = 0f
    private var buffered: Float? = null
    private var target: Float? = null
    private var playing = false
    private val ink = context.themeColor(R.attr.tbColorDsInk)
    private val selected = context.themeColor(R.attr.tbColorDsSelected)
    private val thumbColor = context.themeColor(R.attr.tbColorDsOnToast)
    private val trackPaint = paint(ColorUtils.setAlphaComponent(ink, TRACK_ALPHA))
    private val bufferedPaint = paint(ColorUtils.setAlphaComponent(ink, BUFFERED_ALPHA))
    private val playedPaint = paint(selected)
    private val previewPaint = paint(ColorUtils.setAlphaComponent(selected, PREVIEW_ALPHA))
    private val thumbPaint = paint(thumbColor)
    private val markPaint = paint(ColorUtils.setAlphaComponent(thumbColor, MARK_ALPHA))
    private val ringPaint = paint(context.themeColor(R.attr.tbColorDsFocus))
    private val lineHeight = resources.getDimension(R.dimen.video_osd_line_height)
    private val thumbPlaying = resources.getDimension(R.dimen.video_osd_thumb_radius_playing)
    private val thumbPaused = resources.getDimension(R.dimen.video_osd_thumb_radius)
    private val thumbSeek = resources.getDimension(R.dimen.video_osd_thumb_radius_seek)
    private val ring = resources.getDimension(R.dimen.video_osd_thumb_ring)
    private val markRadius = resources.getDimension(R.dimen.video_osd_mark_radius)
    private val rect = RectF()

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /**
     * Parts of the duration: [position] where the video is, [buffered] how far it is loaded (null if unknown),
     * [target] where a seek goes (null while none goes); [playing]: the thumb is smaller while the video plays
     */
    fun setProgress(position: Float, buffered: Float?, target: Float?, playing: Boolean) {
        val p = position.coerceIn(0f, 1f)
        val b = buffered?.coerceIn(0f, 1f)
        val t = target?.coerceIn(0f, 1f)
        if (p == this.position && b == this.buffered && t == this.target && playing == this.playing) return
        this.position = p
        this.buffered = b
        this.target = t
        this.playing = playing
        invalidate()
    }

    /** The line between the thumbs at the ends (the one while paused fits in the view) */
    private val lineLeft get() = paddingLeft + thumbPaused
    private val lineRight get() = width - paddingRight - thumbPaused

    private fun xOf(part: Float) = lineLeft + (lineRight - lineLeft) * part

    /** Where the thumb is, from the start of the view: where a seek goes, else where the video is */
    fun thumbX(): Float = xOf(target ?: position)

    /** The top of the line, from the top of the view */
    fun lineTop(): Float = height / 2f - lineHeight / 2

    override fun onDraw(canvas: Canvas) {
        if (lineRight <= lineLeft) return
        val cy = height / 2f
        val radius = lineHeight / 2
        fun segment(from: Float, to: Float, paint: Paint) {
            if (to <= from) return
            rect.set(xOf(from), cy - radius, xOf(to), cy + radius)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        segment(0f, 1f, trackPaint)
        buffered?.let { segment(0f, it, bufferedPaint) }
        val target = target
        if (target == null) {
            segment(0f, position, playedPaint)
            canvas.drawCircle(xOf(position), cy, if (playing) thumbPlaying else thumbPaused, thumbPaint)
            return
        }
        //a seek: played up to the nearer of the two, the way between them lighter, a mark where the video is
        segment(0f, minOf(position, target), playedPaint)
        segment(minOf(position, target), maxOf(position, target), previewPaint)
        canvas.drawCircle(xOf(position), cy, markRadius, markPaint)
        canvas.drawCircle(xOf(target), cy, thumbSeek + ring, ringPaint)
        canvas.drawCircle(xOf(target), cy, thumbSeek, thumbPaint)
    }

    companion object {
        private const val TRACK_ALPHA = 0x3D
        private const val BUFFERED_ALPHA = 0x6B
        private const val PREVIEW_ALPHA = 0x73
        private const val MARK_ALPHA = 0xCC
    }
}
