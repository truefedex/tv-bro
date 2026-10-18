package com.phlox.tvwebbrowser.activity.main.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewPhoneSnackbarBinding
import kotlin.math.abs

/**
 * A snackbar of the phone UI over the page: an icon, a title and a caption, and a text button that does
 * an action and hides it. It hides by itself after a while; a swipe to the side dismisses it. A new
 * message replaces the one shown.
 */
class PhoneSnackbar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val vb: ViewPhoneSnackbarBinding
    private val hideRunnable = Runnable { hide() }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var swiping = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.ds_phone_snackbar_min_height)
        val padding = resources.getDimensionPixelSize(R.dimen.ds_phone_snackbar_padding)
        setPadding(0, padding, padding, padding)
        setBackgroundResource(R.drawable.ds_toast_bg)
        visibility = GONE
        vb = ViewPhoneSnackbarBinding.inflate(LayoutInflater.from(context), this)
    }

    /** Shows the message; [actionLabel] and [action] make the button, none if null */
    fun show(@DrawableRes icon: Int, title: String, caption: String?, actionLabel: String?, action: (() -> Unit)?) {
        removeCallbacks(hideRunnable)
        vb.ivIcon.setImageResource(icon)
        vb.tvTitle.text = title
        vb.tvCaption.text = caption
        vb.tvCaption.isVisible = caption != null
        vb.btnAction.isVisible = actionLabel != null && action != null
        vb.btnAction.text = actionLabel
        vb.btnAction.setOnClickListener {
            hide()
            action?.invoke()
        }
        animate().cancel()
        if (!isVisible) {
            visibility = VISIBLE
            alpha = 0f
            translationX = 0f
            animate().alpha(1f).setDuration(ANIMATION_DURATION).start()
        } else {
            alpha = 1f
            translationX = 0f
        }
        postDelayed(hideRunnable, SHOWN_DURATION)
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        if (!isVisible) return
        animate().cancel()
        animate().alpha(0f).setDuration(ANIMATION_DURATION).withEndAction { visibility = GONE }.start()
    }

    /** A horizontal drag moves it, and a long enough one dismisses it */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX - downX) > touchSlop) swiping = true
        }
        return swiping
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                removeCallbacks(hideRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                translationX = event.rawX - downX
                alpha = 1f - minOf(1f, abs(translationX) / width)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (abs(translationX) > width / 3f) {
                    val end = if (translationX > 0) width.toFloat() else -width.toFloat()
                    animate().translationX(end).alpha(0f).setDuration(ANIMATION_DURATION)
                        .withEndAction { visibility = GONE }.start()
                } else {
                    animate().translationX(0f).alpha(1f).setDuration(ANIMATION_DURATION).start()
                    postDelayed(hideRunnable, SHOWN_DURATION)
                }
                swiping = false
            }
        }
        return true
    }

    companion object {
        private const val SHOWN_DURATION = 5000L
        private const val ANIMATION_DURATION = 200L
    }
}
