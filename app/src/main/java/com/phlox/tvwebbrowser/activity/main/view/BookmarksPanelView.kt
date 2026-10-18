package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Row
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Screen
import com.phlox.tvwebbrowser.databinding.ViewBookmarkRowBinding
import com.phlox.tvwebbrowser.databinding.ViewBookmarksPanelBinding
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The TV bookmarks panel, under the star of the bar, over the page: the screens of [BookmarksNavigator]
 * (a folder, the form of a bookmark or folder, the choice of a folder, the search). OK opens a bookmark in
 * the current tab or a folder in the panel; the button at the end of a row opens its options. The search
 * button turns the header into the search field (OK on it opens the keyboard; typing letters in a folder
 * starts the search with them), Down goes from the field to the results. Back goes back a screen (ends the
 * search), and closes the panel on the first one; the star gets the focus back.
 */
class BookmarksPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    private lateinit var scope: CoroutineScope
    private lateinit var menu: PopupMenuView
    private lateinit var navigator: BookmarksNavigator
    private lateinit var callback: BookmarksNavigator.ShellCallback

    private val vb = ViewBookmarksPanelBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llPanel
    private val adapter = Adapter()
    private var screen: Screen? = null
    /** The panel is shown once its first screen is ready */
    private var pendingShow: (() -> Unit)? = null

    init {
        vb.llPanel.clipToOutline = true
        vb.rvItems.layoutManager = LinearLayoutManager(context)
        vb.rvItems.itemAnimator = null
        vb.rvItems.adapter = adapter
        vb.ibBack.setOnClickListener { onBack() }
        vb.ibMenu.setOnClickListener { showPanelMenu() }
        vb.ibSearch.setOnClickListener { navigator.startSearch() }
        vb.ibVoice.setOnClickListener {
            hideKeyboard()
            callback.startVoiceSearch { text ->
                if (screen !is Screen.Search) return@startVoiceSearch
                vb.etSearch.setText(text)
                vb.etSearch.setSelection(vb.etSearch.length())
            }
        }
        vb.etSearch.doAfterTextChanged { text ->
            if (screen is Screen.Search) navigator.search(text?.toString() ?: "")
        }
        //the search key of the keyboard goes to the results
        vb.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            if (adapter.rows.isNotEmpty()) focusRow(null, 0)
            true
        }
        vb.llThisPage.setOnClickListener { navigator.onThisPageClick() }
        vb.llFolder.setOnClickListener { navigator.chooseFormFolder() }
        vb.btnSave.setOnClickListener { saveForm() }
        vb.btnCancel.setOnClickListener { onBack() }
        vb.btnDelete.setOnClickListener { navigator.deleteFormItem() }
        val saveOnDone = TextView.OnEditorActionListener { view, actionId, _ ->
            val last = view == vb.etUrl || (screen as? Screen.Form)?.isFolder == true
            if (last && (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT)) {
                saveForm()
                true
            } else false
        }
        vb.etName.setOnEditorActionListener(saveOnDone)
        vb.etUrl.setOnEditorActionListener(saveOnDone)
    }

    fun attach(scope: CoroutineScope, menu: PopupMenuView, callback: BookmarksNavigator.ShellCallback) {
        this.scope = scope
        this.menu = menu
        this.callback = callback
        navigator = BookmarksNavigator(context, TVBro.container.data.favorites, callback.bookmarkFiles, scope, object : BookmarksNavigator.Host {
            override fun currentPage() = callback.currentPage()

            override fun render(screen: Screen, focus: BookmarksNavigator.Focus) = this@BookmarksPanelView.render(screen, focus)

            override fun openBookmark(url: String) {
                hide()
                callback.openBookmark(url)
            }

            override fun openBookmarkInNewTab(url: String) {
                hide()
                callback.openBookmarkInNewTab(url)
            }

            override fun onBookmarksChanged() = callback.onBookmarksChanged()

            override fun showMessage(icon: Int, message: String) = callback.showMessage(icon, message)

            override fun confirm(message: String, action: () -> Unit) = callback.confirm(message, action)

            override fun close() = hide()
        })
    }

    /**
     * Opens the panel under the [anchor] (the star), its end under the end of [endAlignTo], with the
     * top level of the bookmarks; the bookmark opened last is focused, else the first row.
     */
    fun show(anchor: View, endAlignTo: View) {
        if (isOpen) return
        pendingShow = {
            anchor.isActivated = true
            showUnder(anchor, resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap),
                if (adapter.rows.isEmpty()) fallbackFocus() else vb.rvItems, endAlignTo,
                resources.getDimensionPixelSize(R.dimen.ds_bookmarks_panel_margin_bottom))
        }
        navigator.start()
    }

    override fun onBack() {
        hideKeyboard()
        if (!navigator.back()) hide()
    }

    override fun onHidden() {
        menu.hide()
        hideKeyboard()
        navigator.stop()
        pendingShow = null
        screen = null
        anchor?.isActivated = false
    }

    private fun render(screen: Screen, focus: BookmarksNavigator.Focus) {
        //the results follow the typing: the keyboard stays open
        if (screen !is Screen.Search) hideKeyboard()
        this.screen = screen
        vb.tvTitle.text = screen.title
        vb.ibBack.isVisible = screen.canGoBack
        vb.ibBack.contentDescription = context.getString(if (screen is Screen.Search) R.string.end_search else R.string.back)
        vb.tvTitle.isVisible = screen !is Screen.Search
        vb.llSearch.isVisible = screen is Screen.Search
        vb.ibSearch.isVisible = screen is Screen.Folder
        vb.tvStatus.isVisible = screen is Screen.Search && screen.status != null
        when (screen) {
            is Screen.Folder -> {
                showSections(list = true, thisPage = screen.thisPage != null, form = false)
                vb.ibMenu.isVisible = true
                screen.thisPage?.let {
                    vb.ivThisPage.setImageResource(if (it.bookmarked) R.drawable.ic_edit else R.drawable.ic_favorites)
                    vb.tvThisPageTitle.text = it.title
                    vb.tvThisPageCaption.text = it.caption
                }
                vb.tvEmpty.isVisible = screen.rows.isEmpty()
                adapter.rows = screen.rows
            }
            is Screen.PickFolder -> {
                showSections(list = true, thisPage = false, form = false)
                vb.ibMenu.isVisible = false
                vb.tvEmpty.isVisible = false
                adapter.rows = screen.rows
            }
            is Screen.Search -> {
                showSections(list = true, thisPage = false, form = false)
                vb.ibMenu.isVisible = false
                vb.tvEmpty.isVisible = false
                vb.tvStatus.text = screen.status
                //set once: the field is not reset while it is typed in
                if (vb.etSearch.text.toString() != screen.query) {
                    vb.etSearch.setText(screen.query)
                    vb.etSearch.setSelection(vb.etSearch.length())
                }
                adapter.rows = screen.rows
            }
            is Screen.Form -> {
                showSections(list = false, thisPage = false, form = true)
                vb.ibMenu.isVisible = false
                vb.etName.setText(screen.name)
                vb.etName.imeOptions = if (screen.isFolder) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                vb.etUrl.setText(screen.url)
                vb.tvUrlLabel.isVisible = !screen.isFolder
                vb.etUrl.isVisible = !screen.isFolder
                vb.tvFolderLabel.isVisible = !screen.isFolder
                vb.llFolder.isVisible = !screen.isFolder
                vb.tvFolder.text = screen.folderTitle
                vb.btnDelete.isVisible = screen.canDelete
            }
        }
        pendingShow?.let {
            pendingShow = null
            it()
        }
        when {
            screen is Screen.Form -> if (focus.key == BookmarksNavigator.FORM_FOLDER) {
                vb.llFolder.requestFocus()
            } else {
                vb.etName.requestFocus()
                vb.etName.setSelection(vb.etName.length())
            }
            focus.key == BookmarksNavigator.SEARCH_FIELD -> if (!vb.etSearch.hasFocus()) vb.etSearch.requestFocus()
            focus.key == BookmarksNavigator.SEARCH_BUTTON -> vb.ibSearch.requestFocus()
            else -> focusRow(focus.key, focus.index)
        }
    }

    /** Letters typed in a folder (on a remote with a keyboard) start the search with them */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isOpen && screen is Screen.Folder && event.action == KeyEvent.ACTION_DOWN && findFocus() !is EditText &&
            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            val char = event.unicodeChar.toChar()
            if (event.unicodeChar != 0 && char.isLetter()) {
                navigator.startSearch(char.toString())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun saveForm() {
        when (navigator.saveForm(vb.etName.text.toString(), vb.etUrl.text.toString())) {
            BookmarksNavigator.Field.NAME -> vb.etName.requestFocus()
            BookmarksNavigator.Field.URL -> vb.etUrl.requestFocus()
            null -> hideKeyboard()
        }
    }

    /** The ⋮ of the header: what is not about one bookmark */
    private fun showPanelMenu() {
        val folder = screen as? Screen.Folder ?: return
        val items = listOf(
            PopupMenuView.Item(R.drawable.ic_create_new_folder, context.getString(R.string.new_folder),
                enabled = folder.canCreateFolder) { navigator.createFolder() },
            PopupMenuView.Item(R.drawable.ic_export, context.getString(R.string.export_bookmarks)) { navigator.export() }
        )
        menu.show(vb.ibMenu, items, alignEnd = true)
    }

    private fun showRowMenu(row: Row, anchor: View) {
        val items = navigator.actionsOf(row).map { action ->
            PopupMenuView.Item(action.action.icon, context.getString(action.action.label), action.enabled) {
                navigator.perform(row, action.action)
            }
        }
        if (items.isNotEmpty()) menu.show(anchor, items, alignEnd = true)
    }

    private fun showSections(list: Boolean, thisPage: Boolean, form: Boolean) {
        vb.llThisPage.isVisible = thisPage
        vb.vThisPageSeparator.isVisible = thisPage
        vb.rvItems.isVisible = list
        if (!list) vb.tvEmpty.isVisible = false
        vb.llForm.isVisible = form
    }

    /** What gets the focus when the list has no rows */
    private fun fallbackFocus(): View = when {
        vb.llSearch.isVisible -> vb.etSearch
        vb.llThisPage.isVisible -> vb.llThisPage
        vb.ibMenu.isVisible -> vb.ibMenu
        else -> vb.ibBack
    }

    /** Focuses the row of the [key], else the row at [index] (the last one if there are fewer) */
    private fun focusRow(key: String?, index: Int) {
        val rows = adapter.rows
        var position = rows.indexOfFirst { it.key == key }
        if (position == -1) position = minOf(index, rows.size - 1)
        if (position < 0) {
            fallbackFocus().requestFocus()
            return
        }
        vb.rvItems.scrollToPosition(position)
        requestRowFocus(position, ROW_FOCUS_ATTEMPTS)
    }

    /** The row's view exists after the next layout of the list */
    private fun requestRowFocus(position: Int, attempts: Int) {
        vb.rvItems.post {
            if (!isOpen) return@post
            val holder = vb.rvItems.findViewHolderForAdapterPosition(position) as? Holder
            when {
                holder != null -> holder.vb.llMain.requestFocus()
                attempts > 0 -> requestRowFocus(position, attempts - 1)
                else -> fallbackFocus().requestFocus()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ViewBookmarkRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position], position)
    }

    private inner class Holder(val vb: ViewBookmarkRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var row: Row? = null
        private val iconTint = ColorStateList.valueOf(context.themeColor(R.attr.tbColorDsIcon))
        private val inset = resources.getDimensionPixelSize(R.dimen.ds_space_menu_inset)
        private val indent = resources.getDimensionPixelSize(R.dimen.ds_bookmark_folder_indent)

        init {
            vb.llMain.setOnClickListener { row?.let { navigator.onRowClick(it) } }
            vb.ibOptions.setOnClickListener { row?.let { showRowMenu(it, vb.ibOptions) } }
        }

        fun bind(row: Row, position: Int) {
            this.row = row
            vb.vDivider.isVisible = position > 0
            val depth = (row as? Row.Target)?.depth ?: 0
            vb.llMain.setPaddingRelative(inset + depth * indent, vb.llMain.paddingTop, vb.llMain.paddingEnd,
                vb.llMain.paddingBottom)
            vb.llMain.isActivated = row is Row.Target && row.chosen
            bindBookmarkRow(row, BookmarkRowViews(vb.ivIcon, vb.tvTitle, vb.tvCaption, vb.ivEnd, vb.ibOptions), iconTint) { url ->
                loadFavicon(row, url)
            }
        }

        private fun loadFavicon(row: Row, url: String) {
            scope.launch {
                val favicon = FaviconsPool.get(url) ?: return@launch
                //the holder may show another row by now
                if (this@Holder.row?.key != row.key) return@launch
                ImageViewCompat.setImageTintList(vb.ivIcon, null)
                vb.ivIcon.setImageBitmap(favicon)
            }
        }
    }

    companion object {
        private const val ROW_FOCUS_ATTEMPTS = 3
    }
}
