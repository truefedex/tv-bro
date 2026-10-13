package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.ScrollView
import com.phlox.tvwebbrowser.R

/**
 * Root container for one settings category. Rows are declared as [SettingsItem]s and rendered by
 * [SettingsRowFactory]; call [setItems] (or [rebuild]) whenever the declared set/visibility changes.
 */
open class SettingsPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var items: List<SettingsItem> = emptyList()

    init {
        isFillViewport = true
        addView(container)
        val hPad = resources.getDimensionPixelSize(R.dimen.settings_page_horizontal_padding)
        val vPad = resources.getDimensionPixelSize(R.dimen.settings_row_vertical_padding)
        container.setPadding(hPad, vPad, hPad, vPad)
    }

    fun setItems(items: List<SettingsItem>) {
        this.items = items
        rebuild()
    }

    /** Recreates the visible rows, keeping the currently focused row focused when possible. */
    fun rebuild() {
        val focusedIndex = container.focusedChild?.let { container.indexOfChild(it) } ?: -1
        container.removeAllViews()
        for (item in items) {
            if (!item.isVisible()) continue
            container.addView(SettingsRowFactory.create(context, item))
        }
        if (focusedIndex in 0 until container.childCount) {
            container.getChildAt(focusedIndex).requestFocus()
        }
    }
}
