package com.phlox.tvwebbrowser.activity.main.view

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A menu taller than the screen (the regional filter lists) fits it and scrolls to the focused row */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class PopupMenuViewTest {
    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()
    private val root = FrameLayout(activity)
    private val anchor = Button(activity)
    private val menu = PopupMenuView(activity)

    init {
        root.addView(anchor, FrameLayout.LayoutParams(200, 100).apply { topMargin = 300 })
        root.addView(menu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(root)
        idle()
    }

    @Test
    fun longMenuFitsTheScreen() {
        menu.show(anchor, items(60), alignEnd = false)
        idle()
        val scroll = scrollView()
        assertTrue(scroll.top >= 0 && scroll.height > root.height / 2)
        assertTrue("menu bottom ${scroll.bottom} > screen ${root.height}", scroll.bottom <= root.height)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
    }

    @Test
    fun focusedLastRowIsScrolledIntoView() {
        menu.show(anchor, items(60), alignEnd = false)
        idle()
        val last = rows().last()
        last.requestFocus()
        idle()
        assertTrue(last.isFocused)
        assertVisible(last)
    }

    @Test
    fun rowFocusedOnOpenIsInView() {
        menu.show(anchor, items(60), alignEnd = false, focusIndex = 55)
        idle()
        val row = rows()[55]
        assertTrue(row.isFocused)
        assertVisible(row)
    }

    @Test
    fun reopenedMenuStartsAtTheTop() {
        menu.show(anchor, items(60), alignEnd = false, focusIndex = 59)
        idle()
        menu.hide()
        menu.show(anchor, items(60), alignEnd = false)
        idle()
        assertEquals(0, scrollView().scrollY)
        assertVisible(rows().first())
    }

    private fun items(count: Int) = List(count) { PopupMenuView.Item(R.drawable.ic_close, "Item $it") {} }

    private fun scrollView(): ScrollView = menu.findViewById(R.id.svMenu)

    private fun rows(): List<View> {
        val list = menu.findViewById<ViewGroup>(R.id.llMenu)
        return (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable }
    }

    private fun assertVisible(row: View) {
        val scroll = scrollView()
        val rect = Rect()
        row.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(row, rect)
        rect.offset(0, -scroll.scrollY)
        assertTrue("row $rect out of the menu (height ${scroll.height})", rect.top >= 0 && rect.bottom <= scroll.height)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
