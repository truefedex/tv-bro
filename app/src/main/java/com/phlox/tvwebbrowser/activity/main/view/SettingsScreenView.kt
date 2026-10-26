package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Category
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Key
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Row
import com.phlox.tvwebbrowser.databinding.ViewSettingsScreenBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import com.phlox.tvwebbrowser.utils.themeColor
import kotlinx.coroutines.CoroutineScope

/**
 * The settings on TV, over the whole window: the categories on the left (the focused one is shown) and the
 * rows of the shown one in a card on the right, drawn from a [SettingsNavigator]. Left from the card goes
 * back to the shown category, right from a category goes into its card. A choice that is not segmented opens
 * its options in the popup menu of the shell. Back closes the screen; [onClosed] gives the focus back.
 */
class SettingsScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val vb: ViewSettingsScreenBinding
    private lateinit var navigator: SettingsNavigator
    private lateinit var popupMenu: PopupMenuView
    private lateinit var onClosed: () -> Unit
    private val categoryViews = LinkedHashMap<Category, View>()
    private var shownPage: Category? = null

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        orientation = VERTICAL
        visibility = GONE
        //clicks and the cursor do not reach the page under it
        isClickable = true
        setBackgroundColor(context.themeColor(R.attr.tbColorDsBgChrome))
        vb = ViewSettingsScreenBinding.inflate(LayoutInflater.from(context), this)
        vb.ibBack.setOnClickListener { hide() }
        vb.btnRestart.setOnClickListener {
            vb.vRows.commitFields()
            navigator.restart()
        }
        vb.vRows.touch = false
        //the restart strip follows the rounded corners of the card
        vb.llCard.clipToOutline = true
    }

    fun attach(scope: CoroutineScope, popupMenu: PopupMenuView, callback: SettingsNavigator.ShellCallback, onClosed: () -> Unit) {
        this.popupMenu = popupMenu
        this.onClosed = onClosed
        vb.vRows.callback = rowsCallback
        navigator = SettingsNavigator(context, callback.store, scope, Host(callback), tvUi = true)
        val inflater = LayoutInflater.from(context)
        for (category in navigator.categories) {
            val row = inflater.inflate(R.layout.view_settings_category, vb.llCategories, false)
            row.findViewById<ImageView>(R.id.ivIcon).setImageResource(category.icon)
            row.findViewById<TextView>(R.id.tvTitle).setText(category.title)
            //the focused category is shown
            row.setOnFocusChangeListener { _, focused -> if (focused) navigator.open(category) }
            row.setOnClickListener {
                navigator.open(category)
                vb.vRows.firstFocusable()?.requestFocus()
            }
            vb.llCategories.addView(row)
            categoryViews[category] = row
        }
    }

    fun show(category: Category? = null) {
        if (isOpen) {
            if (category != null) categoryViews[category]?.requestFocus()
            return
        }
        visibility = VISIBLE
        backCallback.isEnabled = true
        shownPage = null
        navigator.show(category)
        categoryViews[navigator.page]?.requestFocus()
    }

    fun hide() {
        if (!isOpen) return
        vb.vRows.commitFields()
        popupMenu.hide()
        navigator.onHidden()
        visibility = GONE
        backCallback.isEnabled = false
        onClosed()
    }

    private fun render(screen: SettingsNavigator.Screen) {
        for (item in screen.categories) {
            val view = categoryViews[item.category] ?: continue
            view.isActivated = item.category == screen.page
            val badge = view.findViewById<TextView>(R.id.tvBadge)
            badge.text = item.badge
            badge.isVisible = item.badge != null
        }
        val page = screen.page
        vb.tvCategoryTitle.text = page?.let { context.getString(it.title) }
        vb.vRows.render(screen.sections)
        if (page != shownPage) {
            shownPage = page
            vb.svRows.scrollTo(0, 0)
        }
        vb.llRestart.isVisible = screen.restartPending
    }

    private fun inCard(view: View) = isDescendant(vb.llCard, view)

    private fun inRail(view: View) = isDescendant(vb.llCategories, view)

    private fun isDescendant(parent: View, view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v == parent) return true
            v = v.parent as? View
        }
        return false
    }

    /**
     * Keys move the focus only inside the screen: right from a category into its card, left from the card
     * back to the shown category; up and down do not leave the card for the categories
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!isOpen) return super.focusSearch(focused, direction)
        val next = FocusFinder.getInstance().findNextFocus(this, focused, direction)
        if (focused != null && inRail(focused) && direction == FOCUS_RIGHT) {
            return vb.vRows.firstFocusable() ?: vb.btnRestart.takeIf { vb.llRestart.isVisible } ?: focused
        }
        if (focused != null && inCard(focused) && (next == null || !inCard(next))) {
            return when (direction) {
                FOCUS_LEFT -> categoryViews[navigator.page] ?: focused
                else -> focused
            }
        }
        return next ?: focused
    }

    private val rowsCallback = object : SettingsRowsView.Callback {
        override fun openChoice(row: Row.Choice, anchor: View) {
            val items = row.options.mapIndexed { i, option ->
                PopupMenuView.Item(R.drawable.ic_check, option, checked = i == row.selected) {
                    navigator.choose(row.key, i)
                }
            }
            popupMenu.show(anchor, items, alignEnd = true, width = R.dimen.ds_settings_menu_width,
                activateAnchor = true, focusIndex = row.selected)
        }

        override fun choose(key: Key, index: Int) = navigator.choose(key, index)
        override fun toggle(row: Row.Toggle) = navigator.toggle(row)
        override fun setSlider(key: Key, value: Int) = navigator.setSlider(key, value)
        override fun setText(key: Key, text: String) = navigator.setText(key, text)
        override fun act(row: Row) = navigator.act(row)
    }

    private inner class Host(private val callback: SettingsNavigator.ShellCallback) : SettingsNavigator.Host {
        override fun render(screen: SettingsNavigator.Screen) = this@SettingsScreenView.render(screen)
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
}
