package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Category
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Key
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Row
import com.phlox.tvwebbrowser.databinding.ViewPhoneSettingsBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.utils.themeColor
import kotlinx.coroutines.CoroutineScope

/**
 * The settings page of the phone UI, over the whole window like the history: the cards of the categories,
 * then the page of a category, drawn from a [SettingsNavigator]. A choice that is not segmented opens its
 * options in the sheet of the shell. Back ([backCallback]) goes from a category to the list and closes the
 * page from the list.
 */
class PhoneSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {
    private val vb: ViewPhoneSettingsBinding
    private lateinit var navigator: SettingsNavigator
    private lateinit var sheet: PhoneActionsSheet
    private var shownPage: Category? = null
    private var shownCategories: List<SettingsNavigator.CategoryItem>? = null
    /** The restart strip changes which view keeps out of the navigation bar */
    private var lastInsets = IntArray(5)

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            vb.vRows.commitFields()
            if (!navigator.back()) hide()
        }
    }

    val isOpen: Boolean get() = isVisible

    init {
        //under the status bar it has the color of its header
        setBackgroundColor(context.themeColor(R.attr.tbColorDsBgChrome))
        isClickable = true
        visibility = GONE
        vb = ViewPhoneSettingsBinding.inflate(LayoutInflater.from(context), this)
        vb.ibBack.setOnClickListener { backCallback.handleOnBackPressed() }
        vb.btnRestart.setOnClickListener {
            vb.vRows.commitFields()
            navigator.restart()
        }
        vb.vRows.touch = true
    }

    fun attach(scope: CoroutineScope, sheet: PhoneActionsSheet, callback: SettingsNavigator.ShellCallback) {
        this.sheet = sheet
        vb.vRows.callback = rowsCallback
        navigator = SettingsNavigator(context, callback.store, scope, Host(callback), tvUi = false)
    }

    /** The content scrolls under the navigation bar and the keyboard */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, keyboard: Int) {
        setPadding(left, top, right, if (vb.llRestart.isVisible) maxOf(bottom, keyboard) else 0)
        vb.svContent.setPadding(0, 0, 0, if (vb.llRestart.isVisible) 0 else maxOf(bottom, keyboard))
        lastInsets = intArrayOf(left, top, right, bottom, keyboard)
    }

    /** Opens the list of the categories, or the [category] */
    fun show(category: Category? = null) {
        if (!isOpen || !backCallback.isEnabled) {
            backCallback.isEnabled = true
            animate().cancel()
            visibility = VISIBLE
            alpha = 0f
            translationY = height * HIDDEN_SHIFT
            animate().alpha(1f).translationY(0f).setDuration(ANIMATION_DURATION).start()
        }
        shownPage = null
        shownCategories = null
        navigator.show(category)
    }

    fun hide() {
        if (!isOpen) return
        vb.vRows.commitFields()
        hideKeyboard()
        sheet.hide()
        navigator.onHidden()
        backCallback.isEnabled = false
        animate().cancel()
        animate().alpha(0f).translationY(height * HIDDEN_SHIFT).setDuration(ANIMATION_DURATION)
            .withEndAction { visibility = GONE }.start()
    }

    private fun hideKeyboard() {
        val focused = findFocus() ?: return
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private fun render(screen: SettingsNavigator.Screen) {
        val page = screen.page
        vb.tvTitle.text = context.getString(page?.title ?: R.string.settings)
        vb.llCategories.isVisible = page == null
        vb.vRows.isVisible = page != null
        if (page == null) {
            if (shownCategories != screen.categories) {
                shownCategories = screen.categories
                renderCategories(screen.categories)
            }
        } else {
            vb.vRows.render(screen.sections)
        }
        if (page != shownPage) {
            if (shownPage != null && page == null) hideKeyboard()
            shownPage = page
            vb.svContent.scrollTo(0, 0)
        }
        if (vb.llRestart.isVisible != screen.restartPending) {
            vb.llRestart.isVisible = screen.restartPending
            val (left, top, right, bottom, keyboard) = lastInsets
            setInsets(left, top, right, bottom, keyboard)
        }
    }

    /** The categories in a card, About in a card of its own */
    private fun renderCategories(items: List<SettingsNavigator.CategoryItem>) {
        vb.llCategories.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val margin = resources.getDimensionPixelSize(R.dimen.ds_phone_settings_card_margin)
        val gap = resources.getDimensionPixelSize(R.dimen.ds_phone_settings_cards_gap)
        for (group in items.partition { it.category != Category.ABOUT }.toList().filter { it.isNotEmpty() }) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ds_section_bg)
                clipToOutline = true
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                dividerDrawable = context.getDrawable(R.drawable.ds_settings_divider)
            }
            for (item in group) {
                val row = inflater.inflate(R.layout.view_phone_settings_category, card, false)
                row.findViewById<ImageView>(R.id.ivIcon).setImageResource(item.category.icon)
                row.findViewById<TextView>(R.id.tvTitle).text = item.title
                row.findViewById<TextView>(R.id.tvSummary).text = item.summary
                val badge = row.findViewById<TextView>(R.id.tvBadge)
                badge.text = item.badge
                badge.isVisible = item.badge != null
                row.setOnClickListener { navigator.open(item.category) }
                card.addView(row)
            }
            vb.llCategories.addView(card, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = margin
                rightMargin = margin
                topMargin = gap
            })
        }
    }

    private val rowsCallback = object : SettingsRowsView.Callback {
        override fun openChoice(row: Row.Choice, anchor: View) {
            val items = row.options.mapIndexed { i, option ->
                PopupMenuView.Item(R.drawable.ic_check, option, checked = i == row.selected) {
                    navigator.choose(row.key, i)
                }
            }
            sheet.show(row.title, null, items)
        }

        override fun choose(key: Key, index: Int) = navigator.choose(key, index)
        override fun toggle(row: Row.Toggle) = navigator.toggle(row)
        override fun setSlider(key: Key, value: Int) = navigator.setSlider(key, value)
        override fun setText(key: Key, text: String) = navigator.setText(key, text)
        override fun act(row: Row) = navigator.act(row)
    }

    private inner class Host(private val callback: SettingsNavigator.ShellCallback) : SettingsNavigator.Host {
        override fun render(screen: SettingsNavigator.Screen) = this@PhoneSettingsView.render(screen)
        override fun showMessage(icon: Int, message: String) = callback.showMessage(icon, message)
        override fun confirm(title: String?, message: String, positive: String, onResult: (Boolean) -> Unit) =
            callback.confirm(title, message, positive, onResult)
        override fun captureKey(shortcut: Shortcut, onDone: () -> Unit) = callback.captureKey(shortcut, onDone)

        override fun pickDownloadFolder(onDone: () -> Unit) = callback.pickDownloadFolder(onDone)

        override fun pickFromList(title: String, options: List<String>, checked: Int, onChosen: (Int) -> Unit) =
            callback.pickFromList(title, options, checked, onChosen)

        override fun open(url: String) {
            hide()
            callback.open(url)
        }

        override fun startUpdate() {
            hide()
            callback.startUpdate()
        }

        override fun restart() = callback.restart()
    }

    companion object {
        private const val ANIMATION_DURATION = 200L
        private const val HIDDEN_SHIFT = 0.05f
    }
}
