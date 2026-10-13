package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.phlox.tvwebbrowser.R

/** Builds the concrete row [View] for a [SettingsItem], in the native Android TV settings style. */
object SettingsRowFactory {

    fun create(context: Context, item: SettingsItem): View = when (item) {
        is SettingsItem.Header -> createHeader(context, item)
        is SettingsItem.Toggle -> createToggle(context, item)
        is SettingsItem.Choice -> createChoice(context, item)
        is SettingsItem.TextInput -> createTextInput(context, item)
        is SettingsItem.Action -> createAction(context, item)
        is SettingsItem.Slider -> createSlider(context, item)
        is SettingsItem.KeyBinding -> createKeyBinding(context, item)
        is SettingsItem.Info -> createInfo(context, item)
    }

    private fun inflate(context: Context, layoutRes: Int): View =
        LayoutInflater.from(context).inflate(layoutRes, null)

    private fun createHeader(context: Context, item: SettingsItem.Header): View {
        val row = inflate(context, R.layout.view_settings_row_header)
        row.findViewById<TextView>(R.id.tvSectionHeader).setText(item.titleRes)
        return row
    }

    private fun bindIconAndText(row: View, iconRes: Int, titleRes: Int, summaryRes: Int?) {
        row.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        val summary = row.findViewById<TextView>(R.id.tvSummary)
        if (summaryRes != null) {
            summary.setText(summaryRes)
            summary.visibility = View.VISIBLE
        } else {
            summary.visibility = View.GONE
        }
    }

    private fun createToggle(context: Context, item: SettingsItem.Toggle): View {
        val row = inflate(context, R.layout.view_settings_row_switch)
        bindIconAndText(row, item.iconRes, item.titleRes, item.summaryRes)
        val toggle = row.findViewById<SwitchCompat>(R.id.swToggle)
        toggle.isChecked = item.isChecked()
        row.setOnClickListener {
            val newValue = !item.isChecked()
            toggle.isChecked = newValue
            item.onCheckedChange(newValue)
        }
        return row
    }

    private fun createChoice(context: Context, item: SettingsItem.Choice): View {
        val row = inflate(context, R.layout.view_settings_row_value)
        bindIconAndText(row, item.iconRes, item.titleRes, item.summaryRes)
        row.findViewById<TextView>(R.id.tvValue).text = item.value()
        row.setOnClickListener { showChoiceDialog(context, item) }
        return row
    }

    private fun createTextInput(context: Context, item: SettingsItem.TextInput): View {
        val row = inflate(context, R.layout.view_settings_row_value)
        bindIconAndText(row, item.iconRes, item.titleRes, item.summaryRes)
        row.findViewById<TextView>(R.id.tvValue).text = item.value()
        row.setOnClickListener {
            promptText(context, item.titleRes, item.value(), item.hint()) { item.onCommit(it) }
        }
        return row
    }

    private fun createAction(context: Context, item: SettingsItem.Action): View {
        val row = inflate(context, R.layout.view_settings_row_action)
        bindIconAndText(row, item.iconRes, item.titleRes, item.summaryRes)
        val progress = row.findViewById<View>(R.id.pbProgress)
        val inProgress = item.isInProgress()
        progress.visibility = if (inProgress) View.VISIBLE else View.GONE
        row.isEnabled = !inProgress
        row.setOnClickListener {
            if (item.isInProgress()) return@setOnClickListener
            item.onClick()
        }
        return row
    }

    private fun createSlider(context: Context, item: SettingsItem.Slider): View {
        val row = inflate(context, R.layout.view_settings_row_slider)
        row.findViewById<ImageView>(R.id.ivIcon).setImageResource(item.iconRes)
        row.findViewById<TextView>(R.id.tvTitle).setText(item.titleRes)
        val valueLabel = row.findViewById<TextView>(R.id.tvValue)
        val seekBar = row.findViewById<SeekBar>(R.id.sbValue)
        seekBar.max = item.max - item.min
        seekBar.progress = (item.value() - item.min).coerceIn(0, item.max - item.min)
        valueLabel.text = item.valueText(item.value())
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = item.min + progress
                valueLabel.text = item.valueText(value)
                item.onValueChange(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        return row
    }

    private fun createKeyBinding(context: Context, item: SettingsItem.KeyBinding): View {
        val row = inflate(context, R.layout.view_settings_row_value)
        bindIconAndText(row, item.iconRes, item.titleRes, null)
        row.findViewById<TextView>(R.id.tvValue).text = item.value()
        row.setOnClickListener { item.onClick() }
        return row
    }

    private fun createInfo(context: Context, item: SettingsItem.Info): View {
        val row = inflate(context, R.layout.view_settings_row_info)
        val icon = row.findViewById<ImageView>(R.id.ivIcon)
        if (item.iconRes != null) {
            icon.setImageResource(item.iconRes)
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }
        row.findViewById<TextView>(R.id.tvTitle).setText(item.titleRes)
        row.findViewById<TextView>(R.id.tvValue).text = item.value()
        return row
    }

    private fun showChoiceDialog(context: Context, item: SettingsItem.Choice) {
        AlertDialog.Builder(context)
            .setTitle(item.titleRes)
            .setSingleChoiceItems(item.entries().toTypedArray(), item.selectedIndex()) { dialog, which ->
                dialog.dismiss()
                item.onSelected(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Opens a single-line text input dialog. Shared by text rows and "Custom…" choice flows. */
    fun promptText(
        context: Context,
        titleRes: Int,
        initial: CharSequence,
        hint: CharSequence,
        onCommit: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            setText(initial)
            this.hint = hint.toString()
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        val pad = (context.resources.displayMetrics.density * 24).toInt()
        val wrapper = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(wrapper)
            .setPositiveButton(R.string.ok) { _, _ -> onCommit(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
