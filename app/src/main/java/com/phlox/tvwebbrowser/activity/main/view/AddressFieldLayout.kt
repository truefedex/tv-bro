package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * The address field: its border shows the state of the address inside it (focused, or edited when
 * activated), not of the buttons at its ends, which show their own focus.
 */
class AddressFieldLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    /** The address (or its text while it is edited) has the focus */
    var addressFocused = false
        set(value) {
            if (field == value) return
            field = value
            refreshDrawableState()
        }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace + 1)
        if (addressFocused) {
            mergeDrawableStates(state, intArrayOf(android.R.attr.state_focused))
        }
        return state
    }
}
