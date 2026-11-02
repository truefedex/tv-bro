package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * The address field of the bars, which keeps a soft keyboard off the address completed inline
 * ([InlineAddressCompletion], the selected rest of the address): its Backspace comes as
 * [InputConnection.deleteSurroundingText], which deletes the character before the selection and keeps the
 * selection, so [removeCompletion] removes the completion instead, and only that; and a keyboard composing a
 * word replaces the word and not the selection, so the completion is removed before the word changes.
 */
class AddressEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {
    /** Removes the completion shown in the field; false when none is shown */
    var removeCompletion: (() -> Boolean)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, true) {
            //a keyboard composing a word replaces the word, not the selection: the completion goes first
            //(the text typed is completed again after the edit)
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                removeCompletion?.invoke()
                return super.setComposingText(text, newCursorPosition)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                removeCompletion?.invoke()
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && removeCompletion?.invoke() == true) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && removeCompletion?.invoke() == true) return true
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
        }
    }
}
