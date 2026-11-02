package com.phlox.tvwebbrowser.activity.main.view

import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions
import com.phlox.tvwebbrowser.model.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class InlineAddressCompletionTest {
    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()
    private val field = AddressEditText(activity).also { activity.setContentView(it) }
    private val entries = AddressSuggestions.aggregate(listOf(
        HistoryItem().apply { url = "http://www.youtube.com/watch?v=1"; time = 2 },
        HistoryItem().apply { url = "https://yoga.com/"; time = 1 }
    ), now = 3)
    private val typed = ArrayList<String>()
    private val completion = InlineAddressCompletion(field, { AddressSuggestions.inlineCandidate(entries, it) }) { typed += it }

    /** Types the [text] as a keyboard does: in place of the selection; the completion comes after it */
    private fun type(text: String) {
        val start = field.selectionStart
        field.text!!.replace(start, field.selectionEnd, text)
        field.setSelection(start + text.length)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun backspace() {
        val start = field.selectionStart
        val end = field.selectionEnd
        if (start != end) field.text!!.delete(start, end) else field.text!!.delete(start - 1, start)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertField(text: String, selectionStart: Int, selectionEnd: Int = text.length) {
        assertEquals(text, field.text.toString())
        assertEquals(selectionStart, field.selectionStart)
        assertEquals(selectionEnd, field.selectionEnd)
    }

    @Test
    fun theRestOfTheAddressIsAddedAndSelected() {
        completion.start()
        type("y")
        assertField("youtube.com", 1)
        assertEquals(listOf("y"), typed)
        //the next letter takes the place of the completion
        type("o")
        assertField("youtube.com", 2)
        type("g")
        assertField("yoga.com", 3)
        assertEquals(listOf("y", "yo", "yog"), typed)
        assertEquals("https://yoga.com/", completion.urlFor("yoga.com"))
    }

    @Test
    fun backspaceRemovesTheCompletionAndDoesNotBringItBack() {
        completion.start()
        type("yo")
        assertField("youtube.com", 2)
        backspace()
        assertField("yo", 2)
        assertNull(completion.urlFor("yo"))
        backspace()
        assertField("y", 1)
    }

    @Test
    fun theSubmittedCompletionOpensTheUrlOfItsSite() {
        completion.start()
        type("you")
        assertEquals("http://www.youtube.com/", completion.urlFor("youtube.com"))
        //text edited after the completion is not the completion any more
        field.setSelection(field.length())
        type("/feed")
        assertNull(completion.urlFor("youtube.com/feed"))
    }

    @Test
    fun nothingIsCompletedWhileNotActiveOrInTheMiddleOfTheText() {
        field.setText("yo")
        assertField("yo", 0, 0)
        assertTrue(typed.isEmpty())
        completion.start()
        field.setSelection(0)
        type("w")
        assertEquals("wyo", field.text.toString())
        completion.stop()
        field.setText("y")
        assertEquals("y", field.text.toString())
    }

    /** Typing of a soft keyboard: through the input connection of the field */
    private val ic by lazy { field.onCreateInputConnection(EditorInfo())!! }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aKeyboardKeepsTheCompletionSelected() {
        field.requestFocus()
        completion.start()
        ic.commitText("y", 1)
        idle()
        assertField("youtube.com", 1)
        ic.commitText("o", 1)
        idle()
        assertField("youtube.com", 2)
    }

    @Test
    fun theBackspaceOfAKeyboardRemovesOnlyTheCompletion() {
        field.requestFocus()
        completion.start()
        ic.commitText("yo", 1)
        idle()
        assertField("youtube.com", 2)
        ic.deleteSurroundingText(1, 0)
        idle()
        assertField("yo", 2)
        ic.deleteSurroundingText(1, 0)
        idle()
        assertField("y", 1)
    }

    @Test
    fun aComposedWordIsCompletedAndTheNextLetterReplacesTheCompletion() {
        field.requestFocus()
        completion.start()
        ic.setComposingText("yo", 1)
        idle()
        assertField("youtube.com", 2)
        ic.setComposingText("yog", 1)
        idle()
        assertField("yoga.com", 3)
        ic.finishComposingText()
        idle()
        assertField("yoga.com", 3)
        assertEquals("https://yoga.com/", completion.urlFor("yoga.com"))
    }
}
