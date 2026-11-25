package com.phlox.tvwebbrowser.activity.main.view

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.BrowsingDataChoice
import com.phlox.tvwebbrowser.browser.BrowsingDataKind
import com.phlox.tvwebbrowser.webengine.DataClearing
import com.phlox.tvwebbrowser.webengine.DataProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ClearBrowsingDataDialogTest {
    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()

    private fun boxesOf(view: View): List<CheckBox> = when (view) {
        is CheckBox -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { boxesOf(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun boxesFollowTheChoice() {
        //GeckoView: main and kids together, incognito not at all
        val choice = BrowsingDataChoice(listOf(DataProfile.MAIN, DataProfile.KIDS, DataProfile.INCOGNITO),
            { if (it == DataProfile.INCOGNITO) DataClearing.NONE else DataClearing.WITH_OTHERS }, emptySet())
        var cleared = false
        val dialog = ClearBrowsingDataDialog.show(activity, choice, listOf("note")) { cleared = true }
        //the rows (history, tabs, bookmarks, downloads, site data, site settings, cache), each with a box for each
        //profile (main, kids, incognito)
        val boxes = boxesOf(dialog.window!!.decorView)
        assertEquals(BrowsingDataKind.entries.size * 3, boxes.size)
        fun box(kind: BrowsingDataKind, column: Int) = boxes[kind.ordinal * 3 + column]
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertFalse(positive.isEnabled)
        //incognito: only its tabs; the engine clears none of its data
        for (kind in BrowsingDataKind.entries) {
            assertEquals(kind.name, kind == BrowsingDataKind.TABS, box(kind, 2).isEnabled)
            assertTrue(kind.name, box(kind, 0).isEnabled)
        }

        //the site data of the kids profile goes with those of the main one
        box(BrowsingDataKind.SITE_DATA, 1).performClick()
        assertTrue(box(BrowsingDataKind.SITE_DATA, 0).isChecked)
        assertTrue(box(BrowsingDataKind.SITE_DATA, 1).isChecked)
        assertFalse(box(BrowsingDataKind.SITE_DATA, 2).isChecked)
        assertTrue(positive.isEnabled)
        //the app's own data apart
        box(BrowsingDataKind.BOOKMARKS, 0).performClick()
        assertTrue(box(BrowsingDataKind.BOOKMARKS, 0).isChecked)
        assertFalse(box(BrowsingDataKind.BOOKMARKS, 1).isChecked)

        positive.performClick()
        //the dialog posts the click of its button
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(cleared)
    }
}
