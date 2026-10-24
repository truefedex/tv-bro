package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Key
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Row
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Section

/**
 * The rows of a category of the settings, in their sections. The rows are a form, not a list: there are few
 * of them and some have text fields, so each row keeps its view (by [Row.id]) while it is shown, and the
 * focus stays on it when the page changes around it. On TV ([touch] false) the sections follow each other
 * under their titles inside the card of the screen; on the phone each section is a card of its own.
 * The views get their sizes from the theme (ThemeOverlay.TVBro.Settings.Phone on the phone).
 */
class SettingsRowsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    interface Callback {
        /** A choice that is not segmented was clicked: show its options at the [anchor] */
        fun openChoice(row: Row.Choice, anchor: View)
        fun choose(key: Key, index: Int)
        fun toggle(row: Row.Toggle)
        fun setSlider(key: Key, value: Int)
        fun setText(key: Key, text: String)
        fun act(row: Row)
    }

    var touch = false
    lateinit var callback: Callback
    private val inflater = LayoutInflater.from(context)
    private val holders = LinkedHashMap<String, Holder>()
    private var structure: List<Pair<String?, List<String>>> = emptyList()

    init {
        orientation = VERTICAL
    }

    fun render(sections: List<Section>) {
        val shown = HashSet<String>()
        for (section in sections) for (row in section.rows) {
            shown += row.id
            var holder = holders[row.id]
            if (holder == null || !holder.accepts(row)) {
                holder = createHolder(row)
                holders[row.id] = holder
            }
            holder.bind(row)
        }
        holders.keys.retainAll(shown)
        val newStructure = sections.map { section -> section.title to section.rows.map { it.id } }
        if (newStructure == structure) return
        structure = newStructure
        //the focused row is taken out with the others and gets the focus back
        val focusedId = holders.entries.firstOrNull { it.value.view.hasFocus() }?.key
        val focusedChild = holders[focusedId]?.view?.findFocus()
        for (holder in holders.values) (holder.view.parent as? ViewGroup)?.removeView(holder.view)
        removeAllViews()
        for (section in sections) {
            if (section.title != null) {
                val title = inflater.inflate(R.layout.view_settings_group, this, false) as TextView
                title.text = section.title
                if (touch) {
                    title.updatePadding(left = dimen(R.dimen.ds_phone_settings_group_padding_horizontal),
                        right = dimen(R.dimen.ds_phone_settings_group_padding_horizontal),
                        top = dimen(R.dimen.ds_phone_settings_group_padding_top),
                        bottom = dimen(R.dimen.ds_settings_row_vertical_padding))
                } else {
                    title.updatePadding(left = dimen(R.dimen.ds_settings_group_padding_horizontal),
                        right = dimen(R.dimen.ds_settings_group_padding_horizontal),
                        top = dimen(R.dimen.ds_settings_group_padding_top),
                        bottom = dimen(R.dimen.ds_settings_group_padding_bottom))
                }
                addView(title)
            }
            val container: ViewGroup = if (touch) {
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setBackgroundResource(R.drawable.ds_section_bg)
                    clipToOutline = true
                    showDividers = SHOW_DIVIDER_MIDDLE
                    dividerDrawable = context.getDrawable(R.drawable.ds_settings_divider)
                    val margin = dimen(R.dimen.ds_phone_settings_card_margin)
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = margin
                        rightMargin = margin
                        if (section.title == null) topMargin = dimen(R.dimen.ds_phone_settings_group_padding_top)
                    }
                    this@SettingsRowsView.addView(this)
                }
            } else this
            for (row in section.rows) container.addView(holders.getValue(row.id).view)
        }
        if (focusedChild != null && focusedChild.isAttachedToWindow) {
            focusedChild.requestFocus()
        }
    }

    /** Saves the texts typed in the fields (before the screen closes or leaves the page) */
    fun commitFields() {
        for (holder in holders.values) (holder as? FieldHolder)?.commit()
    }

    /** The view the focus goes to when it comes into the rows: the first row that takes it */
    fun firstFocusable(): View? = holders.values.firstNotNullOfOrNull { it.focusTarget() }

    private fun dimen(id: Int) = resources.getDimensionPixelSize(id)

    private fun createHolder(row: Row): Holder = when (row) {
        is Row.Choice -> ChoiceHolder()
        is Row.Toggle -> ToggleHolder()
        is Row.Slider -> SliderHolder()
        is Row.Field -> FieldHolder()
        is Row.Action -> ActionHolder()
        is Row.ShortcutKey -> KeyHolder()
        is Row.Link -> LinkHolder()
        is Row.Info -> InfoHolder()
    }

    private abstract inner class Holder(@LayoutRes layout: Int) {
        val view: View = inflater.inflate(layout, this@SettingsRowsView, false)
        private val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        private val tvTag: TextView = view.findViewById(R.id.tvTag)
        private val tvSummary: TextView = view.findViewById(R.id.tvSummary)
        protected lateinit var row: Row

        abstract fun accepts(row: Row): Boolean

        open fun bind(row: Row) {
            this.row = row
            tvTitle.text = row.title
            tvTag.isVisible = row.restart
            tvSummary.text = row.summary
            tvSummary.isVisible = !row.summary.isNullOrEmpty()
        }

        /** The view of the row that takes the focus, null if none */
        open fun focusTarget(): View? = view.takeIf { it.isFocusable }
    }

    private inner class ChoiceHolder : Holder(R.layout.view_settings_row_choice) {
        private val llValue: View = view.findViewById(R.id.llValue)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val llSegments: LinearLayout = view.findViewById(R.id.llSegments)
        private var segmentOptions: List<String>? = null

        init {
            llSegments.clipToOutline = true
            view.setOnClickListener { (row as? Row.Choice)?.let { if (!it.segmented) callback.openChoice(it, view) } }
        }

        override fun accepts(row: Row) = row is Row.Choice && row.segmented == (this.row as Row.Choice).segmented

        override fun bind(row: Row) {
            super.bind(row)
            row as Row.Choice
            llValue.isVisible = !row.segmented
            llSegments.isVisible = row.segmented
            view.isFocusable = !row.segmented
            view.isClickable = !row.segmented
            view.contentDescription = if (row.segmented) null else "${row.title}: ${row.options.getOrNull(row.selected)}"
            if (!row.segmented) {
                tvValue.text = row.options.getOrNull(row.selected)
                return
            }
            if (touch && view is LinearLayout) {
                //the segments fill a line under the text
                (view as LinearLayout).orientation = VERTICAL
                (view as LinearLayout).gravity = android.view.Gravity.START
                llSegments.layoutParams = (llSegments.layoutParams as MarginLayoutParams).apply {
                    width = LayoutParams.MATCH_PARENT
                    marginStart = 0
                    topMargin = dimen(R.dimen.ds_settings_field_gap)
                }
                view.findViewById<View>(R.id.llText).layoutParams =
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                view.background = null
            }
            if (segmentOptions != row.options) {
                segmentOptions = row.options
                val hadFocus = llSegments.hasFocus()
                llSegments.removeAllViews()
                for ((i, option) in row.options.withIndex()) {
                    if (i > 0) inflater.inflate(R.layout.view_settings_segment_separator, llSegments, true)
                    val segment = inflater.inflate(R.layout.view_settings_segment, llSegments, false) as TextView
                    segment.text = option
                    if (touch) {
                        segment.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    }
                    segment.setOnClickListener { callback.choose(row.key, i) }
                    llSegments.addView(segment)
                }
                if (hadFocus) segments().getOrNull(row.selected)?.requestFocus()
            }
            for ((i, segment) in segments().withIndex()) {
                segment.isActivated = i == row.selected
                segment.contentDescription = "${row.title}: ${row.options[i]}"
            }
        }

        private fun segments(): List<View> = (0 until llSegments.childCount).map { llSegments.getChildAt(it) }
            .filter { it is TextView }

        /** The focus comes to the selected option */
        override fun focusTarget(): View? {
            val row = row as Row.Choice
            return if (row.segmented) segments().getOrNull(row.selected) ?: segments().firstOrNull() else view
        }
    }

    private inner class ToggleHolder : Holder(R.layout.view_settings_row_toggle) {
        private val swValue: SwitchCompat = view.findViewById(R.id.swValue)

        init {
            view.setOnClickListener { callback.toggle(row as Row.Toggle) }
        }

        override fun accepts(row: Row) = row is Row.Toggle

        override fun bind(row: Row) {
            super.bind(row)
            row as Row.Toggle
            if (swValue.isChecked != row.checked) swValue.isChecked = row.checked
            view.contentDescription = row.title
            ViewCompat.setStateDescription(view, context.getString(if (row.checked) R.string.settings_on else R.string.settings_off))
        }
    }

    private inner class SliderHolder : Holder(R.layout.view_settings_row_slider) {
        private val sbValue: SeekBar = view.findViewById(R.id.sbValue)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private var min = 0

        init {
            sbValue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) callback.setSlider(row.key, min + progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }

        override fun accepts(row: Row) = row is Row.Slider

        override fun bind(row: Row) {
            super.bind(row)
            row as Row.Slider
            min = row.min
            if (sbValue.max != row.max - row.min) {
                sbValue.max = row.max - row.min
                //setMax makes the step a twentieth of the range
                sbValue.keyProgressIncrement = SLIDER_STEP
            }
            if (sbValue.progress != row.value - row.min) sbValue.progress = row.value - row.min
            tvValue.text = row.label
            sbValue.contentDescription = row.title
        }

        override fun focusTarget(): View = sbValue
    }

    private inner class FieldHolder : Holder(if (touch) R.layout.view_phone_settings_row_field else R.layout.view_settings_row_field) {
        private val etValue: EditText = view.findViewById(R.id.etValue)
        private val flAction: View = view.findViewById(R.id.flAction)
        private val btnAction: TextView = view.findViewById(R.id.btnAction)
        private val pbBusy: ProgressBar = view.findViewById(R.id.pbBusy)
        /** The text the row was bound with: a different one in the field was typed */
        private var boundText: String? = null

        init {
            view.background = null
            etValue.setOnFocusChangeListener { _, focused -> if (!focused) commit() }
            etValue.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    commit()
                    true
                } else false
            }
            btnAction.setOnClickListener {
                commit()
                callback.act(row)
            }
        }

        override fun accepts(row: Row) = row is Row.Field

        override fun bind(row: Row) {
            super.bind(row)
            row as Row.Field
            //what the user is typing stays
            if (!etValue.hasFocus() || etValue.text.toString() == boundText) {
                if (etValue.text.toString() != row.text) etValue.setText(row.text)
            }
            boundText = row.text
            etValue.hint = row.hint
            etValue.contentDescription = row.title
            flAction.isVisible = row.action != null
            btnAction.text = row.action
            btnAction.visibility = if (row.busy) INVISIBLE else VISIBLE
            pbBusy.isVisible = row.busy
        }

        fun commit() {
            val text = etValue.text.toString()
            if (text != boundText) callback.setText(row.key, text)
        }

        override fun focusTarget(): View = etValue
    }

    private inner class ActionHolder : Holder(R.layout.view_settings_row_action) {
        private val btnAction: TextView = view.findViewById(R.id.btnAction)

        init {
            btnAction.setOnClickListener { callback.act(row) }
        }

        override fun accepts(row: Row) = row is Row.Action

        override fun bind(row: Row) {
            super.bind(row)
            btnAction.text = (row as Row.Action).button
            btnAction.contentDescription = "${row.button}: ${row.title}"
        }

        override fun focusTarget(): View = btnAction
    }

    private inner class KeyHolder : Holder(R.layout.view_settings_row_key) {
        private val tvKey: TextView = view.findViewById(R.id.tvKey)

        init {
            view.setOnClickListener { callback.act(row) }
        }

        override fun accepts(row: Row) = row is Row.ShortcutKey

        override fun bind(row: Row) {
            super.bind(row)
            val binding = (row as Row.ShortcutKey).binding
            tvKey.text = binding.toDisplayString(context)
            //a key that is set looks like a key cap
            if (binding.isSet) tvKey.setBackgroundResource(R.drawable.ds_settings_key_bg) else tvKey.background = null
            view.contentDescription = "${row.title}: ${tvKey.text}"
        }
    }

    private inner class LinkHolder : Holder(R.layout.view_settings_row_link) {
        init {
            view.setOnClickListener { callback.act(row) }
        }

        override fun accepts(row: Row) = row is Row.Link
    }

    private inner class InfoHolder : Holder(R.layout.view_settings_row_info) {
        override fun accepts(row: Row) = row is Row.Info
        override fun focusTarget(): View? = null
    }

    companion object {
        /** Left and right change a slider by this many percents */
        const val SLIDER_STEP = 5
    }
}
