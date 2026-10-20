package com.phlox.tvwebbrowser.widgets.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.view.WindowManager
import com.phlox.tvwebbrowser.utils.Utils

/**
 * Draws the virtual cursor (normal, pressed and grab looks).
 * Purely visual: knows nothing about input or the emulated pointer.
 */
class CursorRenderer(context: Context) {
    private val paint = Paint().apply { isAntiAlias = true }
    private val cursorRadius: Int
    private val cursorRadiusPressed: Int
    private val strokeWidth: Float

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displaySize = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getSize(displaySize)
        strokeWidth = (displaySize.x / 400).toFloat()
        cursorRadius = displaySize.x / 100
        cursorRadiusPressed = cursorRadius - Utils.D2P(context, 5f).toInt()
    }

    /** [radiusMultiplier] grows the cursor while it appears (not when pressed) */
    fun draw(canvas: Canvas, position: PointF, pressed: Boolean, grab: Boolean, radiusMultiplier: Float) {
        val cx = position.x
        val cy = position.y
        val radius = if (pressed) cursorRadiusPressed.toFloat() else cursorRadius * radiusMultiplier

        paint.color = if (grab) Color.argb(128, 200, 200, 255) else Color.argb(128, 255, 255, 255)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = Color.GRAY
        paint.strokeWidth = strokeWidth
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius, paint)

        if (grab) {
            val halfRadius = radius / 2
            canvas.drawLine(cx - halfRadius, cy, cx + halfRadius, cy, paint)
            canvas.drawLine(cx, cy - halfRadius, cx, cy + halfRadius, paint)
        }
    }
}
