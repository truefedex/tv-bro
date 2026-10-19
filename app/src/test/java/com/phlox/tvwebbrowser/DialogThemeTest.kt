package com.phlox.tvwebbrowser

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.utils.themeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Alert dialogs get the design system look from ThemeOverlay.TVBro.Dialog.Alert, in the day and the night themes */
@RunWith(RobolectricTestRunner::class)
class DialogThemeTest {
    private val activity: AppCompatActivity = Robolectric.buildActivity(AppCompatActivity::class.java)
        .also { it.get().setTheme(R.style.AppTheme) }
        .setup().get()

    @Test
    fun dialogsUseTheDesignSystemOverlay() {
        val dialog = AlertDialog.Builder(activity).setMessage("Message").create()
        assertEquals(R.drawable.ds_dialog_bg, resourceOf(dialog.context, android.R.attr.windowBackground))
        assertEquals(R.drawable.ds_menu_row_bg, resourceOf(dialog.context, androidx.appcompat.R.attr.listChoiceBackgroundIndicator))
        assertEquals(R.style.Widget_TVBro_DialogButton,
            resourceOf(dialog.context, androidx.appcompat.R.attr.buttonBarPositiveButtonStyle))
    }

    @Test
    fun textAndButtonsAreInk() = assertInkTexts()

    @Test
    @Config(qualifiers = "night")
    fun textAndButtonsAreInkAtNight() = assertInkTexts()

    @Test
    fun listItemsAreInk() {
        val dialog = AlertDialog.Builder(activity)
            .setSingleChoiceItems(arrayOf("One", "Two"), 0, null)
            .show()
        val listView = dialog.listView
        val item = listView.adapter.getView(0, null, listView) as TextView
        assertEquals(activity.themeColor(R.attr.tbColorDsInk), item.currentTextColor)
    }

    private fun assertInkTexts() {
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Title")
            .setMessage("Message")
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
        val ink = activity.themeColor(R.attr.tbColorDsInk)
        assertEquals(ink, dialog.findViewById<TextView>(android.R.id.message)!!.currentTextColor)
        assertEquals(ink, dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.currentTextColor)
        for (button in listOf(dialog.getButton(AlertDialog.BUTTON_POSITIVE), dialog.getButton(AlertDialog.BUTTON_NEGATIVE))) {
            assertEquals(ink, button.currentTextColor)
            assertFalse(button.isAllCaps)
        }
    }

    private fun resourceOf(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }
}
