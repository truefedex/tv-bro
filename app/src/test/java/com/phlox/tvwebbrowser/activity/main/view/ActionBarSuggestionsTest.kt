package com.phlox.tvwebbrowser.activity.main.view

import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** The D-pad chooses and opens the suggestions under the address of the TV bar, the field keeps the focus */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class ActionBarSuggestionsTest {
    private lateinit var db: AppDatabase
    private lateinit var activity: AppCompatActivity
    private lateinit var bar: ActionBar
    private lateinit var list: AddressSuggestionsView
    private val log = ArrayList<String>()
    private val history = listOf(
        HistoryItem().apply { url = "http://example.com/a"; title = "Page A"; time = 3 },
        HistoryItem().apply { url = "http://example.com/b"; title = "Page B"; time = 2 },
        HistoryItem().apply { url = "https://other.org/"; title = "Other"; time = 1 }
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.AppTheme) }
            .setup().get()
        bar = ActionBar(activity)
        list = AddressSuggestionsView(activity).apply { loadFavicon = { null } }
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root)
        val suggestions = AddressSuggestions(object : AddressSuggestions.Store {
            override suspend fun all() = history
        }, CoroutineScope(Dispatchers.Unconfined), object : AddressSuggestions.Host {
            override fun render(rows: List<AddressSuggestions.Row>) = list.render(rows)
        }, searchContext = EmptyCoroutineContext, clock = { 4 })
        bar.setSuggestions(suggestions, list)
        bar.callback = object : ActionBar.Callback {
            override fun closeWindow() {}
            override fun navigateBack() {}
            override fun navigateForward() {}
            override fun reloadOrStop() {}
            override fun showDownloads() {}
            override fun showPageVideos() {}
            override fun showFavorites() {}
            override fun showMoreMenu() {}
            override fun initiateVoiceSearch() {}
            override fun search(text: String) { log += "search $text" }
            override fun openUrl(url: String) { log += "open $url" }
            override fun onUrlInputDone() {}
            override fun toggleIncognitoMode() {}
            override fun showSitePanel() {}
        }
        idle()
    }

    @After
    fun tearDown() {
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val field: EditText get() = bar.findViewById(R.id.etUrl)

    private fun startEditing(typed: String) {
        bar.findViewById<android.view.View>(R.id.flUrl).performClick()
        idle()
        field.text.replace(field.selectionStart, field.selectionEnd, typed)
        idle()
    }

    private fun press(keyCode: Int) {
        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        idle()
    }

    @Test
    fun downAndUpChooseASuggestionAndOkOpensIt() {
        startEditing("page")
        assertEquals(listOf("http://example.com/a", "http://example.com/b"), list.rows.map { it.url })
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(1, list.highlighted)
        assertTrue(list.getChildAt(1).isSelected)
        assertTrue(bar.isEditingAddress())
        assertTrue(field.hasFocus())
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(-1, list.highlighted)
        assertTrue(bar.isEditingAddress())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("open http://example.com/a"), log)
        assertFalse(bar.isEditingAddress())
        assertEquals(android.view.View.GONE, list.visibility)
    }

    @Test
    fun enterOpensTheAddressCompletedInlineWithItsScheme() {
        startEditing("exa")
        assertEquals("example.com", field.text.toString())
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("open http://example.com/"), log)
    }

    private fun mouseClick() {
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, 20f, 20f, 0)
            event.source = InputDevice.SOURCE_MOUSE
            field.dispatchTouchEvent(event)
            event.recycle()
        }
        idle()
    }

    /** The text takes the touches of a mouse even while it is not focusable: a click there starts the editing */
    @Test
    fun aMouseClickOnTheAddressStartsTheEditing() {
        mouseClick()
        assertTrue(bar.isEditingAddress())
        assertTrue(field.hasFocus())
        //a click while editing stays in the text
        mouseClick()
        assertTrue(bar.isEditingAddress())
        assertTrue(field.hasFocus())
    }

    @Test
    fun enterSearchesTheTextThatIsNotCompleted() {
        startEditing("page b")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("search page b"), log)
    }
}
