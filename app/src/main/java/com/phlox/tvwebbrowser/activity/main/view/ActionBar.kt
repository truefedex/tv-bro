package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
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

    /**
     * True while the address box is a text field (keyboard up, caret/cursor keys active). It is
     * false while the box merely holds the focus, in which case it behaves like a button.
     */
    private var urlEditMode = false

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
            // Landing on the address box with the remote must not start text editing: otherwise the
            // keyboard pops up unasked and LEFT/RIGHT are swallowed by the caret, so crossing the
            // (wide) url box means stepping over every character instead of reaching the buttons on
            // either side. Editing starts on OK/ENTER, on a tap (see the click listener) or simply
            // by typing on a hardware keyboard.
            setUrlEditMode(false)
        } else {
            dismissExtendedAddressBarMode()
        }
    }

    private val etUrlKeyListener = OnKeyListener { _, keyCode, keyEvent ->
        if (urlEditMode) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (keyEvent.action == KeyEvent.ACTION_UP) commitUrl()
                return@OnKeyListener true
            }
            return@OnKeyListener false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) setUrlEditMode(true, selectAll = true)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                    val direction =
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                    vb.etUrl.focusSearch(direction)?.requestFocus()
                }
                true
            }
            // Editing keys must not change the displayed url while the box is not being edited.
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> true
            else -> {
                // A hardware keyboard means "I want to type this": switch to editing and let it in.
                if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.isPrintingKey) {
                    setUrlEditMode(true, selectAll = false)
                    false
                } else {
                    false
                }
            }
        }
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
        vb.ibSettings.setOnClickListener { callback?.showSettings() }

        vb.etUrl.onFocusChangeListener = etUrlFocusChangeListener

        vb.etUrl.setOnKeyListener(etUrlKeyListener)

        // The framework shows the keyboard when an editable field is tapped, so a touch (or an air
        // mouse) must also leave the box in edit mode, not in its button-like state.
        vb.etUrl.setOnClickListener { setUrlEditMode(true, selectAll = true) }
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
        // A disabled view is still isFocusable(), and FocusFinder's explicit nextFocus chain
        // accepts it (only canTakeFocus() checks enabled, and there is no fallback), so a disabled
        // Back/Forward would trap D-pad focus instead of being skipped. Clear the flag to let the
        // chain walk on to the next button.
        vb.ibBack.isFocusable = enabled
    }

    fun setForwardEnabled(enabled: Boolean) {
        vb.ibForward.isEnabled = enabled
        vb.ibForward.isFocusable = enabled
    }

    fun setMediaCounter(count: Int) {
        // Hide the whole button (not just the badge) when there is nothing to play.
        val visible = count > 0
        vb.flMediaButton.visibility = if (visible) VISIBLE else GONE
        vb.ibMedia.isFocusable = visible
        // The button already means "a stream was detected", so a lone stream needs no number: the
        // badge only adds information once there is a choice to make.
        vb.tvMediaCounter.visibility = if (count > 1) VISIBLE else GONE
        vb.tvMediaCounter.text = count.toString()
    }

    /**
     * Shows the number of open tabs on the tab-panel button and hides the whole button when there is
     * only one tab: the panel is only needed to switch between tabs, and new tabs can still be
     * created from the options menu.
     */
    fun setTabsCounter(count: Int) {
        val visible = count > 1
        vb.flTabsButton.visibility = if (visible) VISIBLE else GONE
        vb.ibTabs.isFocusable = visible
        // As on the stream button, a badge with "1" would say nothing the button does not already.
        vb.tvTabsCounter.visibility = if (count > 1) VISIBLE else GONE
        vb.tvTabsCounter.text = count.toString()
    }

    /** Switches the favorites button between the outline star and the filled star. */
    fun setBookmarked(bookmarked: Boolean) {
        vb.ibFavorites.setImageResource(
            if (bookmarked) R.drawable.ic_star_grey_900_36dp
            else R.drawable.ic_star_border_grey_900_36dp
        )
    }

    /**
     * Shows a small accent dot badge on the settings button while an update is available, so the
     * remote user can tell without opening Settings. The badge is purely decorative (never
     * focusable), so it does not affect the D-pad chain; the content description is updated for
     * accessibility.
     */
    fun setUpdateAvailable(available: Boolean) {
        vb.vSettingsUpdateBadge.visibility = if (available) VISIBLE else GONE
        vb.ibSettings.contentDescription = if (available)
            context.getString(R.string.settings) + ", " + context.getString(R.string.settings_update_available)
        else
            context.getString(R.string.settings)
    }

    /**
     * Switches the address box between its button-like state and text editing. Selecting the whole
     * url ([selectAll]) makes an OK-triggered edit start by replacing the current address.
     */
    private fun setUrlEditMode(editing: Boolean, selectAll: Boolean = false) {
        if (urlEditMode == editing) {
            if (editing && selectAll) selectAllLater()
            return
        }
        urlEditMode = editing
        vb.etUrl.isCursorVisible = editing
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (editing) {
            enterExtendedAddressBarMode()
            imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_IMPLICIT)
            if (selectAll) selectAllLater()
        } else {
            imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
        }
    }

    private fun selectAllLater() {
        postDelayed(//workaround an android TV bug
            {
                if (urlEditMode) vb.etUrl.selectAll()
            }, 500)
    }

    private fun commitUrl() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
        setUrlEditMode(false)
        callback?.search(vb.etUrl.text.toString())
        dismissExtendedAddressBarMode()
        callback?.onUrlInputDone()
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
        setUrlEditMode(false)
        if (!extendedAddressBarMode) return
        extendedAddressBarMode = false
    }

    /**
     * Moves focus from the page into the top bar. Prefers Back (the natural "return" control), but
     * Back/Forward are disabled when the tab has no history and a disabled view cannot take focus,
     * so it falls back to the first shown, focusable button. Without that fallback the remote could
     * stay stuck in the page on a fresh tab.
     */
    fun catchFocus() {
        val candidates = arrayOf(
            vb.ibBack, vb.ibForward, vb.ibRefresh, vb.ibTabs,
            vb.ibFavorites, vb.ibMedia, vb.ibSettings, vb.ibMenu
        )
        candidates.firstOrNull { it.isShown && it.isFocusable }?.requestFocus()
    }
}
