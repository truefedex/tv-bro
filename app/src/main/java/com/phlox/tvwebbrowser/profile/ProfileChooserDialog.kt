package com.phlox.tvwebbrowser.profile

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.R

/**
 * Who is watching: the browser starts with the choice of the profile ([ProfileLock.askWhoIsWatching]), the kids
 * one ([ProfileLock.kidsName]) or the main one. [onChosen] gets true for the main profile (which still asks for the
 * PIN while it is locked), false for the kids one. Back, or a dialog closed otherwise, is the kids profile: a child
 * gets nothing by skipping the choice. A dialog with a window of its own, so its keys do not reach the shortcuts.
 */
class ProfileChooserDialog private constructor(
    activity: AppCompatActivity,
    kidsName: String,
    private val onChosen: (main: Boolean) -> Unit
) {
    companion object {
        fun show(activity: AppCompatActivity, kidsName: String, onChosen: (main: Boolean) -> Unit) {
            ProfileChooserDialog(activity, kidsName, onChosen).dialog.show()
        }
    }

    private var main = false
    private val dialog: AlertDialog

    init {
        val builder = AlertDialog.Builder(activity)
        val context = builder.context
        val resources = context.resources
        val padding = resources.getDimensionPixelSize(R.dimen.ds_phone_form_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.ds_pin_key_gap)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(padding, padding / 2, padding, padding)
        }
        lateinit var dialog: AlertDialog
        fun tile(icon: Int, title: String, isMain: Boolean) = TextView(context, null, 0, R.style.Widget_TVBro_TextButton_Ds).apply {
            text = title
            maxLines = 2
            gravity = Gravity.CENTER
            val size = resources.getDimensionPixelSize(R.dimen.ds_profile_tile_icon)
            setCompoundDrawablesRelative(null, ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
                setBounds(0, 0, size, size)
            }, null, null)
            compoundDrawablePadding = gap
            //the icon sits at the top padding, not with the text: the padding centers both
            setPadding(paddingLeft, padding, paddingRight, padding)
            setOnClickListener {
                main = isMain
                dialog.dismiss()
            }
        }.also {
            row.addView(it, LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.ds_profile_tile_width),
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(gap, 0, gap, 0) })
        }
        //the kids profile first: the focus starts there
        val kids = tile(R.drawable.ic_child, kidsName, false)
        tile(R.drawable.ic_lock, context.getString(R.string.profile_main), true)
        dialog = builder.setTitle(R.string.who_is_watching)
            .setView(row)
            .setOnDismissListener { onChosen(main) }
            .create()
        dialog.setOnShowListener { kids.requestFocus() }
        this.dialog = dialog
    }
}
