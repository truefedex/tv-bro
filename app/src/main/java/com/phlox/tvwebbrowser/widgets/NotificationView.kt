package com.phlox.tvwebbrowser.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.annotation.DrawableRes
import com.phlox.tvwebbrowser.databinding.ViewNotificationBinding

open class NotificationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private var lastAnimator: ViewPropertyAnimator? = null
    private var vb: ViewNotificationBinding

    companion object {
        const val DISAPPEARING_DELAY = 3000L
        const val APPEARING_DURATION = 500L
        const val DISAPPEARING_DURATION = 500L

        private var lastView: NotificationView? = null

        fun showBottomRight(parent: RelativeLayout, @DrawableRes icon: Int, message: String): NotificationView {
            val view = NotificationView(parent.context)
            view.setIcon(icon)
            view.setMessage(message)
            val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            parent.addView(view, lp)
            val lv = lastView
            if (lv != null && lv.isAttachedToWindow) {
                val lvp = lv.parent
                if (lvp != null && lvp is ViewGroup) {
                    lvp.removeView(lv)
                }
                view.postDelayed({
                    view.animateDisappearing()
                }, DISAPPEARING_DELAY)
            } else {
                view.animateAppearing()
            }
            lastView = view
            return view
        }
    }

    init {
        vb = ViewNotificationBinding.inflate(LayoutInflater.from(context), this, true)
    }

    private fun animateDisappearing() {
        lastAnimator = animate().alpha(0f).translationY(height.toFloat()).setDuration(DISAPPEARING_DURATION).withEndAction {
            val parent = parent
            if (parent != null && parent is ViewGroup) {
                parent.removeView(this)
            }
            lastView = null
        }.also { it.start() }
    }

    fun setMessage(text: String) {
        vb.tvMessage.text = text
    }

    fun setIcon(@DrawableRes icon: Int) {
        vb.ivIcon.setImageResource(icon)
    }

    fun animateAppearing() {
        alpha = 0f
        lastAnimator = animate().alpha(1.0f).setDuration(APPEARING_DURATION).withEndAction {
            alpha = 1f
            translationY = 0f
            postDelayed({
                animateDisappearing()
            }, DISAPPEARING_DELAY)
        }.also { it.start() }
    }
}