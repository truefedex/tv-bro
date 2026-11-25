package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.DialogInterface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.BrowsingDataChoice
import com.phlox.tvwebbrowser.browser.BrowsingDataKind
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.webengine.DataProfile

/**
 * Clear browsing data: a table of check boxes, the kinds of data in rows and the profiles in columns (no header
 * while there is only the main one), drawn from a [BrowsingDataChoice], which decides which boxes are disabled
 * and which go together; the [notes] (what goes together, what the incognito mode has) under it. Clear is enabled while a box is
 * checked and runs [onClear].
 */
object ClearBrowsingDataDialog {
    private const val HEADER_MAX_WIDTH_DP = 96f

    fun show(context: Context, choice: BrowsingDataChoice, notes: List<String>, onClear: () -> Unit): AlertDialog {
        val builder = AlertDialog.Builder(context)
        val ctx = builder.context
        val metrics = ctx.resources.displayMetrics
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics).toInt()
        val padding = TypedValue().let {
            ctx.theme.resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, it, true)
            TypedValue.complexToDimensionPixelSize(it.data, metrics)
        }
        val ink = ctx.themeColor(R.attr.tbColorDsInk)
        val faint = ctx.themeColor(R.attr.tbColorDsInkFaint)
        fun text(@androidx.annotation.StyleRes style: Int, value: String, color: Int) =
            TextView(ctx, null, 0, style).apply {
                text = value
                setTextColor(color)
            }

        val table = TableLayout(ctx).apply {
            setColumnStretchable(0, true)
            setColumnShrinkable(0, true)
        }
        val profiles = choice.profiles
        if (profiles.size > 1) {
            val header = TableRow(ctx)
            header.addView(View(ctx))
            for (profile in profiles) {
                header.addView(text(R.style.Widget_TVBro_Text_Caption, ctx.getString(labelOf(profile)), ink).apply {
                    gravity = Gravity.CENTER
                    maxWidth = dp(HEADER_MAX_WIDTH_DP)
                    setPadding(dp(4f), 0, dp(4f), dp(4f))
                }, TableRow.LayoutParams().apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM })
            }
            table.addView(header)
        }

        val boxes = HashMap<Pair<DataProfile, BrowsingDataKind>, CheckBox>()
        var dialog: AlertDialog? = null
        fun refresh() {
            for ((cell, box) in boxes) box.isChecked = choice.isChecked(cell.first, cell.second)
            dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = choice.anyChecked
        }
        for (kind in BrowsingDataKind.entries) {
            val row = TableRow(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            val kindLabel = ctx.getString(labelOf(kind))
            val label = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(0, dp(6f), dp(8f), dp(6f))
                addView(text(R.style.Widget_TVBro_Text_Body, kindLabel, ink))
                hintOf(kind)?.let { addView(text(R.style.Widget_TVBro_Text_Caption, ctx.getString(it), faint)) }
            }
            row.addView(label)
            for (profile in profiles) {
                val box = CheckBox(ctx).apply {
                    background = ContextCompat.getDrawable(ctx, R.drawable.ds_menu_row_bg)
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    isEnabled = choice.isEnabled(profile, kind)
                    contentDescription = if (profiles.size > 1) "$kindLabel, ${ctx.getString(labelOf(profile))}" else kindLabel
                    setOnClickListener {
                        choice.toggle(profile, kind)
                        refresh()
                    }
                }
                boxes[profile to kind] = box
                row.addView(box, TableRow.LayoutParams().apply { gravity = Gravity.CENTER })
            }
            table.addView(row)
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(padding, padding / 3, padding, 0)
            addView(table)
            if (notes.isNotEmpty()) {
                addView(text(R.style.Widget_TVBro_Text_Body, notes.joinToString("\n\n"), ink).apply {
                    setPadding(0, dp(8f), 0, 0)
                })
            }
        }
        val shown = builder
            .setTitle(R.string.clear_browsing_data)
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton(R.string.clear) { _, _ -> onClear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog = shown
        refresh()
        return shown
    }

    @StringRes
    private fun labelOf(profile: DataProfile) = when (profile) {
        DataProfile.MAIN -> R.string.profile_main
        DataProfile.KIDS -> R.string.profile_kids
        DataProfile.INCOGNITO -> R.string.incognito
    }

    @StringRes
    private fun labelOf(kind: BrowsingDataKind) = when (kind) {
        BrowsingDataKind.HISTORY -> R.string.history
        BrowsingDataKind.TABS -> R.string.tabs
        BrowsingDataKind.BOOKMARKS -> R.string.bookmarks
        BrowsingDataKind.DOWNLOADS -> R.string.downloads
        BrowsingDataKind.SITE_DATA -> R.string.clear_browsing_data_site_data
        BrowsingDataKind.SITE_SETTINGS -> R.string.site_settings
        BrowsingDataKind.CACHE -> R.string.clear_browsing_data_cache
    }

    /** What is not plain from the name: what goes, what stays */
    @StringRes
    private fun hintOf(kind: BrowsingDataKind): Int? = when (kind) {
        BrowsingDataKind.DOWNLOADS -> R.string.clear_browsing_data_downloads_hint
        BrowsingDataKind.SITE_DATA -> R.string.clear_browsing_data_site_data_hint
        BrowsingDataKind.SITE_SETTINGS -> R.string.clear_browsing_data_site_settings_hint
        else -> null
    }
}
