package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.FindInPageController
import com.phlox.tvwebbrowser.databinding.ViewTvFindBarBinding

/**
 * The find bar of the TV (Find in page, drawn from [FindInPageController.State]), over the top end of the page:
 * typing searches, the Search key of the keyboard leaves the text for the next match button. Down from the bar
 * gives the page the focus (the shell brings it back to the bar with Back). The buttons of the matches are
 * dimmed while there are none but stay focusable, so the number of presses to each one never changes.
 */
class TvFindBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    interface Callback {
        fun onQueryChanged(text: String)
        fun next()
        fun previous()
        fun close()
        /** The mic of the field: the text is said ([setQuery] with what was recognized) */
        fun voiceSearch()
        /** Down from the bar: the page gets the focus, the bar stays */
        fun focusPage()
    }

    var callback: Callback? = null
    private val vb = ViewTvFindBarBinding.inflate(LayoutInflater.from(context), this)
    /** The text is set from the state: it is no change of the user */
    private var rendering = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.ds_menu_bg)
        val padding = resources.getDimensionPixelSize(R.dimen.tv_find_bar_padding)
        setPadding(padding, padding, padding, padding)
        visibility = GONE

        if (!isInEditMode) {
            vb.etQuery.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!rendering) callback?.onQueryChanged(s?.toString() ?: "")
                }
            })
            vb.etQuery.setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    hideKeyboard()
                    vb.ibNext.requestFocus()
                    true
                } else false
            }
            vb.etQuery.setOnFocusChangeListener { _, hasFocus -> vb.llField.addressFocused = hasFocus }
            vb.ibVoice.setOnClickListener { callback?.voiceSearch() }
            vb.ibPrevious.setOnClickListener { callback?.previous() }
            vb.ibNext.setOnClickListener { callback?.next() }
            vb.ibClose.setOnClickListener { callback?.close() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                hideKeyboard()
                callback?.focusPage()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun render(state: FindInPageController.State?) {
        if (state == null) {
            if (isVisible) {
                hideKeyboard()
                visibility = GONE
            }
            return
        }
        visibility = VISIBLE
        if (vb.etQuery.text.toString() != state.query) {
            rendering = true
            vb.etQuery.setText(state.query)
            vb.etQuery.setSelection(state.query.length)
            rendering = false
        }
        val count = state.countText(resources)
        vb.tvCount.text = count
        vb.tvCount.isVisible = count != null
        //dimmed icons are drawn at 40%
        val alpha = if (state.hasMatches) 255 else 102
        vb.ibPrevious.imageAlpha = alpha
        vb.ibNext.imageAlpha = alpha
    }

    /** The text, selected (typing replaces it), with the keyboard */
    fun focusField() {
        vb.etQuery.requestFocus()
        vb.etQuery.selectAll()
        showKeyboard()
    }

    /** As the address field of the bar does; once more after a layout when the field is not served yet */
    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imm.showSoftInput(vb.etQuery, InputMethodManager.SHOW_IMPLICIT)) {
            vb.etQuery.post { if (vb.etQuery.hasFocus()) imm.showSoftInput(vb.etQuery, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    /** Back to the bar from the page: the next match, or the text while there are no matches */
    fun focusBar(hasMatches: Boolean) {
        if (hasMatches) vb.ibNext.requestFocus() else vb.etQuery.requestFocus()
    }

    /** Recognized speech: searched as typed text */
    fun setQuery(text: String) {
        vb.etQuery.setText(text)
        vb.etQuery.setSelection(text.length)
        vb.ibNext.requestFocus()
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(vb.etQuery.windowToken, 0)
    }
}
