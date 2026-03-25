package com.phlox.tvwebbrowser.activity.main.view

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.phlox.tvwebbrowser.databinding.ViewCursorMenuBinding
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.utils.BackNavigationEventsAdapter

class CursorMenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
): FrameLayout(context, attrs, defStyleAttr) {

    private var vb: ViewCursorMenuBinding =
        ViewCursorMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private var menuContext: MenuContext? = null
    private var handler = Handler(Looper.getMainLooper())
    private var lastShowTime = 0L

    init {
        vb.btnGrabMode.setOnClickListener {
            menuContext?.cursorDrawerDelegate?.goToGrabMode()
            close(CloseAnimation.EXPLODE_OUT)
        }
        vb.btnGrabMode.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && menuContext != null &&
                !(vb.btnContextMenu.hasFocus() || vb.btnDPADMode.hasFocus() ||
                        vb.btnZoomIn.hasFocus() || vb.btnZoomOut.hasFocus())){
                handler.postDelayed({
                    vb.btnGrabMode.requestFocus()
                }, 100)
            }
        }
        vb.btnContextMenu.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                postDelayed({
                    menuContext?.let {
                        with(it) {
                            windowProvider.suggestActionsForLink(baseUri, linkUri, srcUri,
                                title, altText, textContent, x, y)
                        }
                    }
                    close(CloseAnimation.FADE_OUT)
                }, 100)
            }
        }
        vb.btnDPADMode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                postDelayed({
                    menuContext?.tab?.webEngine?.setVirtualCursorMode(false)
                    menuContext?.backNavigationEventsAdapter
                        ?.gameControllersLongPressBForBackNavigation = true
                    close(CloseAnimation.FADE_OUT)
                }, 100)
            }
        }
        vb.btnZoomIn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                postDelayed({
                    menuContext?.tab?.webEngine?.zoomIn()
                    vb.btnGrabMode.requestFocus()
                }, 100)
            }
        }
        vb.btnZoomOut.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                postDelayed({
                    menuContext?.tab?.webEngine?.zoomOut()
                    vb.btnGrabMode.requestFocus()
                }, 100)
            }
        }
    }

    fun show(
        tab: WebTabState,
        windowProvider: WebEngineWindowProviderCallback,
        cursorDrawerDelegate: CursorDrawerDelegate,
        baseUri: String?,
        linkUri: String?,
        srcUri: String?,
        title: String?,
        altText: String?,
        textContent: String?,
        x: Int,
        y: Int,
        backNavigationEventsAdapter: BackNavigationEventsAdapter
    ) {
        Log.d("CursorMenuView", "show: $baseUri, $linkUri, $srcUri, $title, $altText, $textContent, x=$x, y=$y")
        val now = System.currentTimeMillis()
        if (menuContext != null && now - lastShowTime < 1000) {
            return
        }
        lastShowTime = now
        this.menuContext = MenuContext(tab, windowProvider, cursorDrawerDelegate,
            baseUri, linkUri, srcUri, title, altText, textContent, x, y, backNavigationEventsAdapter)
        visibility = VISIBLE
        handler.postDelayed( {
            vb.btnGrabMode.requestFocus()
            tab.webEngine.getCursorDrawerDelegate()?.hideCursor()
        }, 100)
        //set position
        val params = layoutParams as ViewGroup.MarginLayoutParams
        params.leftMargin = x - vb.root.width / 2
        params.topMargin = y - vb.root.height / 2
        layoutParams = params

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 500
        animator.interpolator = OvershootInterpolator()
        animator.addUpdateListener { valueAnimator ->
            val animatedValue = valueAnimator.animatedValue as Float
            vb.root.alpha = animatedValue
            vb.btnZoomOut.x = (vb.root.width * 0.5f - vb.btnZoomOut.width * 0.5f) * (1 - animatedValue)
            vb.btnZoomOut.y = (vb.root.height * 0.25f - vb.btnDPADMode.height * 0.5f) * (1 - animatedValue) + vb.root.height * 0.5f - vb.btnDPADMode.height * 0.5f
            vb.btnZoomIn.x = (vb.root.width * 0.5f - vb.btnZoomIn.width * 0.5f) * animatedValue + vb.root.width * 0.5f - vb.btnZoomIn.width * 0.5f
            vb.btnZoomIn.y = (vb.root.height * 0.25f - vb.btnContextMenu.height * 0.5f) * animatedValue + vb.root.height * 0.25f
            vb.btnContextMenu.y = (vb.root.height * 0.5f - vb.btnContextMenu.height * 0.5f) * (1 - animatedValue)
            vb.btnContextMenu.x = (vb.root.width * 0.25f - vb.btnZoomOut.width * 0.5f) * animatedValue + vb.root.width * 0.25f
            vb.btnDPADMode.y = (vb.root.height * 0.5f - vb.btnDPADMode.height * 0.5f) * animatedValue + vb.root.height * 0.5f - vb.btnDPADMode.height * 0.5f
            vb.btnDPADMode.x = (vb.root.width * 0.25f - vb.btnZoomIn.width * 0.5f) * (1 - animatedValue) + vb.root.width * 0.5f - vb.btnZoomIn.width * 0.5f
        }
        animator.start()
    }

    fun close(animation: CloseAnimation = CloseAnimation.FADE_OUT) {
        Log.d("CursorMenuView", "close")
        menuContext = null
        val animator = ObjectAnimator.ofFloat(vb.root, "alpha", 1f, 0f)
        animator.duration = 250
        animator.interpolator = AccelerateInterpolator()
        animator.addUpdateListener { valueAnimator ->
            val animatedValue = valueAnimator.animatedValue as Float
            vb.root.alpha = animatedValue
            when (animation) {
                CloseAnimation.FADE_OUT -> {
                    //already handled, the same for all animations
                }

                CloseAnimation.ROTATE_OUT -> {
                    vb.root.rotation = 90 * (1 - animatedValue)
                    if (animatedValue == 0f) {
                        visibility = GONE
                        vb.root.rotation = 0f
                    }
                }

                CloseAnimation.EXPLODE_OUT -> {
                    vb.root.scaleX = 1 + 0.5f * (1 - animatedValue)
                    vb.root.scaleY = 1 + 0.5f * (1 - animatedValue)
                    if (animatedValue == 0f) {
                        visibility = GONE
                        vb.root.scaleX = 1f
                        vb.root.scaleY = 1f
                    }
                }
            }
            if (animatedValue == 0f) {
                visibility = GONE
            }
        }
        animator.start()
    }

    enum class CloseAnimation {
        FADE_OUT,
        ROTATE_OUT,
        EXPLODE_OUT
    }

    private data class MenuContext (
        val tab: WebTabState,
        val windowProvider: WebEngineWindowProviderCallback,
        val cursorDrawerDelegate: CursorDrawerDelegate,
        val baseUri: String?,
        val linkUri: String?,
        val srcUri: String?,
        val title: String?,
        val altText: String?,
        val textContent: String?,
        val x: Int,
        val y: Int,
        val backNavigationEventsAdapter: BackNavigationEventsAdapter
    )
}