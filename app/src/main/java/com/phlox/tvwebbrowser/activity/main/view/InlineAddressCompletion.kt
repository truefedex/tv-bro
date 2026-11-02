package com.phlox.tvwebbrowser.activity.main.view

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.InlineCompletion

/**
 * Completes the address typed in the [field] inline: the rest of the address is added after the typed
 * text and selected, so the next character typed replaces it and Backspace removes it. It completes only
 * when the user added text at the end (never after a deletion), also while the keyboard composes a word
 * there (Gboard does in address fields): an [AddressEditText] removes the completion before the keyboard
 * changes its word. Works only while [active] (the address is edited), so the text the bar sets is left alone.
 * @param complete the completion of the typed text, null when nothing fits
 * @param onTyped the text the user typed, without the completion
 */
class InlineAddressCompletion(
    private val field: EditText,
    private val complete: (String) -> InlineCompletion?,
    private val onTyped: (String) -> Unit
) {
    var active = false
        private set
    /** The completion shown (or accepted) in the field */
    private var completion: InlineCompletion? = null
    private var applying = false
    private var addedAtEnd = false

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (applying) return
            addedAtEnd = count > 0 && start + count == s.length
        }

        override fun afterTextChanged(s: Editable) {
            if (applying || !active) return
            val typed = s.toString()
            completion = null
            onTyped(typed)
            field.removeCallbacks(apply)
            if (!addedAtEnd) return
            addedAtEnd = false
            pendingText = typed
            //after the keyboard is done with its edit: it places the cursor after the text it put in
            //(BaseInputConnection.replaceText), which would drop a selection made now
            field.post(apply)
        }
    }

    /** The text typed that waits for its completion */
    private var pendingText: String? = null

    private val apply = Runnable {
        val typed = pendingText ?: return@Runnable
        pendingText = null
        val text = field.text
        if (!active || text.toString() != typed) return@Runnable
        if (field.selectionStart != typed.length || field.selectionEnd != typed.length) return@Runnable
        val candidate = complete(typed) ?: return@Runnable
        if (!candidate.text.startsWith(typed)) return@Runnable
        completion = candidate
        if (candidate.text.length == typed.length) return@Runnable
        applying = true
        try {
            text.append(candidate.text, typed.length, candidate.text.length)
            field.setSelection(typed.length, candidate.text.length)
        } finally {
            applying = false
        }
    }

    init {
        field.addTextChangedListener(watcher)
        (field as? AddressEditText)?.removeCompletion = ::removeShown
    }

    /** Removes the completion while it is shown (the selected rest of the address); false when it is not */
    private fun removeShown(): Boolean {
        val shown = completion ?: return false
        val text = field.text ?: return false
        val start = field.selectionStart
        if (!active || text.toString() != shown.text || start >= field.selectionEnd || field.selectionEnd != text.length) {
            return false
        }
        text.delete(start, text.length)
        return true
    }

    /** The editing started: the text typed from now on is completed */
    fun start() {
        active = true
        completion = null
        addedAtEnd = false
    }

    fun stop() {
        active = false
        completion = null
        pendingText = null
        field.removeCallbacks(apply)
    }

    /** The url to open for the [text] the field is submitted with: that of the completion it shows, if any */
    fun urlFor(text: String): String? = completion?.takeIf { it.text == text }?.url
}
