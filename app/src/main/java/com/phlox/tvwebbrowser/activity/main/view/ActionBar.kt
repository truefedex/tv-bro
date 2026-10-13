package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View.OnFocusChangeListener
import android.view.View.OnKeyListener
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewActionbarBinding

class ActionBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val vb = ViewActionbarBinding.inflate( LayoutInflater.from(context),this)
    var callback: Callback? = null
    private var extendedAddressBarMode = false

    interface Callback {
        fun closeWindow()
        fun showDownloads()
        fun showFavorites()
        fun showHistory()
        fun showSettings()
        fun initiateVoiceSearch()
        fun search(text: String)
        fun onExtendedAddressBarMode()
        fun onUrlInputDone()
        fun toggleIncognitoMode()
        fun onNavigateBack()
        fun onNavigateForward()
        fun onReload()
        fun onShowStreams()
        fun onToggleTabPanel()
        fun onShowMenu()
    }

    private val etUrlFocusChangeListener = OnFocusChangeListener { _, focused ->
        if (focused) {
            enterExtendedAddressBarMode()

            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_IMPLICIT)
            postDelayed(//workaround an android TV bug
                {
                    vb.etUrl.selectAll()
                }, 500)
        } else {
            dismissExtendedAddressBarMode()
        }
    }

    private val etUrlKeyListener = OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
                    callback?.search(vb.etUrl.text.toString())
                    dismissExtendedAddressBarMode()
                    callback?.onUrlInputDone()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    init {
        init()
    }

    fun init() {
        orientation = HORIZONTAL
        // Center the buttons and the (shorter) URL box vertically so the bar is only as tall as
        // its tallest control instead of being inflated by the EditText's own margins.
        gravity = Gravity.CENTER_VERTICAL

        if (isInEditMode) return

        val incognitoMode = AppContext.provideConfig().incognitoMode

        vb.ibTabs.setOnClickListener { callback?.onToggleTabPanel() }
        vb.ibBack.setOnClickListener { callback?.onNavigateBack() }
        vb.ibForward.setOnClickListener { callback?.onNavigateForward() }
        vb.ibRefresh.setOnClickListener { callback?.onReload() }
        vb.ibFavorites.setOnClickListener { callback?.showFavorites() }
        vb.ibMedia.setOnClickListener { callback?.onShowStreams() }
        vb.ibMenu.setOnClickListener { callback?.onShowMenu() }

        vb.etUrl.onFocusChangeListener = etUrlFocusChangeListener

        vb.etUrl.setOnKeyListener(etUrlKeyListener)
    }

    fun setAddressBoxText(text: String) {
        if (Config.isHomePageUrl(text)) {
            vb.etUrl.setText("")
        } else {
            vb.etUrl.setText(text)
        }
    }

    /**
     * Kept for compatibility. The top bar is now pinned and always reserves space, so it never
     * floats over the live page. Buttons always keep their transparent background, so this only
     * adjusts the URL box fill and never touches the button backgrounds.
     */
    fun setOverlayTransparency(transparent: Boolean) {
        vb.flUrl.setBackgroundColor(
            if (transparent) Color.TRANSPARENT
            else ContextCompat.getColor(context, R.color.top_bar_background)
        )
    }

    fun setAddressBoxTextColor(color: Int) {
        vb.etUrl.setTextColor(color)
    }

    fun setBackEnabled(enabled: Boolean) {
        vb.ibBack.isEnabled = enabled
    }

    fun setForwardEnabled(enabled: Boolean) {
        vb.ibForward.isEnabled = enabled
    }

    fun setMediaCounter(count: Int) {
        vb.tvMediaCounter.visibility = if (count > 0) VISIBLE else GONE
        vb.tvMediaCounter.text = count.toString()
    }

    /**
     * The top bar is pinned and always reserves space, so URL-edit mode must never hide the
     * navigation/action buttons: the address box simply expands into the free space (it has
     * layout_weight=1). Hiding them here used to collapse the bar down to just the URL and, worse,
     * if focus left the box without the ENTER handler running (e.g. a link opening a new page)
     * the buttons stayed hidden for good. Entering the mode now only closes the side panels.
     */
    private fun enterExtendedAddressBarMode() {
        if (extendedAddressBarMode) return
        extendedAddressBarMode = true
        callback?.onExtendedAddressBarMode()
    }

    fun dismissExtendedAddressBarMode() {
        if (!extendedAddressBarMode) return
        extendedAddressBarMode = false
    }

    /**
     * Moves focus from the page into the top bar. Prefers Back (the natural "return" control), but
     * Back/Forward are disabled when the tab has no history and a disabled view cannot take focus,
     * so it falls back to the first enabled, visible button. Without that fallback the remote could
     * stay stuck in the page on a fresh tab.
     */
    fun catchFocus() {
        val candidates = arrayOf(
            vb.ibBack, vb.ibForward, vb.ibRefresh, vb.ibTabs,
            vb.ibFavorites, vb.ibMedia, vb.ibMenu
        )
        candidates.firstOrNull { it.isEnabled && it.visibility == VISIBLE }?.requestFocus()
    }
}
