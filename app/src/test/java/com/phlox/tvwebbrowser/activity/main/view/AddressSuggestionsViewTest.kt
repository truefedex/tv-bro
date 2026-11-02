package com.phlox.tvwebbrowser.activity.main.view

import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.Row
import com.phlox.tvwebbrowser.singleton.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h300dp-xhdpi")
class AddressSuggestionsViewTest {
    //the rows look for favicons, which reads the database
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
    }

    @After
    fun tearDown() {
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()

    /** Without room for all rows above the phone bar, the worst ones are cut, the best one stays by the bar */
    @Test
    fun theBestRowStaysNextToThePhoneBar() {
        val root = FrameLayout(activity)
        val list = AddressSuggestionsView(activity)
        list.loadFavicon = { null }
        list.rowLayout = R.layout.view_phone_address_suggestion_row
        list.bestAtBottom = true
        root.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200))
        list.render((1..6).map { Row("https://site$it.com/", "Site $it", "site$it.com", listOf("site")) })
        shadowOf(Looper.getMainLooper()).idle()

        val bestRow = list.getChildAt(list.childCount - 1)
        assertTrue("the rows (${list.childCount}) do not fit ${list.height}", list.getChildAt(0).top < 0)
        assertEquals(list.height, bestRow.bottom)
        assertEquals("Site 1", bestRow.findViewById<android.widget.TextView>(R.id.tvTitle).text.toString())
    }
}
