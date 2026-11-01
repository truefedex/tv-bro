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
import com.phlox.tvwebbrowser.databinding.ViewPhoneFindBarBinding

/**
 * The find bar of the phone UI (Find in page, drawn from [FindInPageController.State]), in the place of the
 * [PhoneBar] at the bottom, above the keyboard: typing searches, the Search key of the keyboard hides it to
 * show the page.
 */
class PhoneFindBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    interface Callback {
        fun onQueryChanged(text: String)
        fun next()
        fun previous()
        fun close()
    }

    var callback: Callback? = null
    private val vb = ViewPhoneFindBarBinding.inflate(LayoutInflater.from(context), this)
    /** The text is set from the state: it is no change of the user */
    private var rendering = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.ds_phone_bar_bg)
        val paddingVertical = resources.getDimensionPixelSize(R.dimen.ds_phone_bar_padding_vertical)
        val paddingHorizontal = resources.getDimensionPixelSize(R.dimen.ds_phone_bar_padding_horizontal)
        setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
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
                    true
                } else false
            }
            vb.etQuery.setOnFocusChangeListener { _, hasFocus -> vb.llPill.isActivated = hasFocus }
            vb.ibPrevious.setOnClickListener { callback?.previous() }
            vb.ibNext.setOnClickListener { callback?.next() }
            vb.ibClose.setOnClickListener { callback?.close() }
        }
    }

    fun render(state: FindInPageController.State?) {
        if (state == null) {
            if (isVisible) {
                hideKeyboard()
                if (vb.etQuery.hasFocus()) vb.etQuery.clearFocus()
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
        //the icon tint dims disabled buttons
        vb.ibPrevious.isEnabled = state.hasMatches
        vb.ibNext.isEnabled = state.hasMatches
    }

    /** The text, selected (typing replaces it), with the keyboard */
    fun focusField() {
        vb.etQuery.requestFocus()
        vb.etQuery.selectAll()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imm.showSoftInput(vb.etQuery, InputMethodManager.SHOW_IMPLICIT)) {
            vb.etQuery.post { if (vb.etQuery.hasFocus()) imm.showSoftInput(vb.etQuery, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(vb.etQuery.windowToken, 0)
    }
}
