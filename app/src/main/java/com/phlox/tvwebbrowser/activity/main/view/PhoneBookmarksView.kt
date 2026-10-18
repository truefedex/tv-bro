package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Row
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Screen
import com.phlox.tvwebbrowser.databinding.ViewPhoneBookmarkRowBinding
import com.phlox.tvwebbrowser.databinding.ViewPhoneBookmarksBinding
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.widgets.NotificationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The bookmarks screen of the phone UI over the whole window, like the tab switcher: the screens of
 * [BookmarksNavigator] (a folder, the form of a bookmark or folder, the choice of a folder, the search). A tap
 * opens a bookmark in the current tab or a folder in place; ⋮ on a row opens the sheet of its actions, a long
 * press picks a row up to drag it to another place. The search button turns the header into the search
 * field with the keyboard open. Back ([backCallback]) goes back a screen (ends the search), and closes the
 * bookmarks on the first one.
 */
class PhoneBookmarksView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {
    private val vb = ViewPhoneBookmarksBinding.inflate(LayoutInflater.from(context), this)
    private lateinit var scope: CoroutineScope
    private lateinit var sheet: PhoneActionsSheet
    private lateinit var callback: BookmarksNavigator.ShellCallback
    private lateinit var navigator: BookmarksNavigator
    private val adapter = Adapter()
    private var screen: Screen? = null
    private var keyboardHeight = 0

    /** Enabled while the bookmarks are open */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onBack()
    }

    val isOpen: Boolean get() = isVisible

    /** The row picked up by a long press, and where it was */
    private var draggedRow: Row? = null
    private var dragStart = 0

    private val drag = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun getMovementFlags(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val row = (viewHolder as Holder).row ?: return 0
            //bookmarks and folders of a folder, not Home page and not the folders to choose
            val draggable = screen is Screen.Folder && navigator.sameKind(row, row)
            return if (draggable) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
        }

        override fun onMove(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            //rows trade places only with rows of their kind
            if (!navigator.sameKind(adapter.rows[from], adapter.rows[to])) return false
            adapter.rows.add(to, adapter.rows.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                draggedRow = (viewHolder as Holder).row
                dragStart = viewHolder.bindingAdapterPosition
            }
        }

        /** The row is dropped: the bookmarks follow the new order */
        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            val row = draggedRow ?: return
            draggedRow = null
            val offset = adapter.rows.indexOf(row) - dragStart
            if (offset != 0) navigator.move(row, offset)
        }
    }

    init {
        //under the status bar it has the color of its header
        setBackgroundColor(context.themeColor(R.attr.tbColorDsBgChrome))
        //touches do not go through to the page
        isClickable = true
        visibility = GONE
        vb.rvItems.layoutManager = LinearLayoutManager(context)
        vb.rvItems.adapter = adapter
        ItemTouchHelper(drag).attachToRecyclerView(vb.rvItems)

        vb.ibBack.setOnClickListener { onBack() }
        vb.ibMenu.setOnClickListener { showScreenMenu() }
        vb.ibSearch.setOnClickListener { navigator.startSearch() }
        vb.ibVoice.setOnClickListener {
            hideKeyboard()
            callback.startVoiceSearch { text ->
                if (screen !is Screen.Search) return@startVoiceSearch
                vb.etSearch.setText(text)
                vb.etSearch.setSelection(vb.etSearch.length())
            }
        }
        vb.ibClear.setOnClickListener {
            vb.etSearch.setText("")
            vb.etSearch.requestFocus()
            showKeyboard(vb.etSearch)
        }
        vb.etSearch.doAfterTextChanged { text ->
            val query = text?.toString() ?: ""
            vb.ibVoice.isVisible = query.isEmpty()
            vb.ibClear.isVisible = query.isNotEmpty()
            if (screen is Screen.Search) navigator.search(query)
        }
        //the search key of the keyboard hides it, to see the results
        vb.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            hideKeyboard()
            true
        }
        vb.llThisPage.setOnClickListener { navigator.onThisPageClick() }
        vb.llFolder.setOnClickListener {
            hideKeyboard()
            navigator.chooseFormFolder()
        }
        vb.btnSave.setOnClickListener { saveForm() }
        vb.llDelete.setOnClickListener {
            hideKeyboard()
            navigator.deleteFormItem()
        }
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

    fun attach(scope: CoroutineScope, sheet: PhoneActionsSheet, callback: BookmarksNavigator.ShellCallback) {
        this.scope = scope
        this.sheet = sheet
        this.callback = callback
        navigator = BookmarksNavigator(context, TVBro.container.data.favorites, callback.bookmarkFiles, scope, object : BookmarksNavigator.Host {
            override fun currentPage() = callback.currentPage()

            override fun render(screen: Screen, focus: BookmarksNavigator.Focus) = this@PhoneBookmarksView.render(screen, focus)

            override fun openBookmark(url: String) {
                hide()
                callback.openBookmark(url)
            }

            override fun openBookmarkInNewTab(url: String) {
                hide()
                callback.openBookmarkInNewTab(url)
            }

            override fun onBookmarksChanged() = callback.onBookmarksChanged()

            //over the bookmarks while they are open
            override fun showMessage(icon: Int, message: String) {
                if (isOpen) NotificationView.show(this@PhoneBookmarksView, icon, message) else callback.showMessage(icon, message)
            }

            override fun confirm(message: String, action: () -> Unit) = callback.confirm(message, action)

            override fun close() = hide()
        })
    }

    /** The content keeps out of the system bars, the form out of the keyboard */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, keyboard: Int) {
        setPadding(left, top, right, bottom)
        keyboardHeight = maxOf(0, keyboard - bottom)
        vb.svForm.setPadding(0, 0, 0, keyboardHeight)
    }

    /** Opens the top level */
    fun show() = navigator.start()

    /** Opens the form of the bookmark; saving or Back closes the bookmarks */
    fun showForm(item: FavoriteItem) = navigator.startWithForm(item)

    /** Adds the page at the top level, the bookmarks stay closed */
    suspend fun addPage(info: BookmarksNavigator.PageInfo) = navigator.addPage(info)

    fun hide() {
        if (!isOpen) return
        hideKeyboard()
        sheet.hide()
        navigator.stop()
        screen = null
        backCallback.isEnabled = false
        animate().cancel()
        animate().alpha(0f).translationY(height * HIDDEN_SHIFT).setDuration(ANIMATION_DURATION)
            .withEndAction { visibility = GONE }.start()
    }

    private fun onBack() {
        hideKeyboard()
        if (!navigator.back()) hide()
    }

    private fun render(screen: Screen, focus: BookmarksNavigator.Focus) {
        val firstScreen = !isOpen || this.screen == null
        val searchOpened = screen is Screen.Search && this.screen !is Screen.Search
        this.screen = screen
        vb.tvTitle.text = screen.title
        when {
            screen is Screen.Search -> {
                vb.ibBack.setImageResource(R.drawable.ic_back)
                vb.ibBack.contentDescription = context.getString(R.string.end_search)
            }
            screen is Screen.Form -> {
                vb.ibBack.setImageResource(R.drawable.ic_close)
                vb.ibBack.contentDescription = context.getString(R.string.cancel)
            }
            screen.canGoBack -> {
                vb.ibBack.setImageResource(R.drawable.ic_back)
                vb.ibBack.contentDescription = context.getString(R.string.back)
            }
            else -> {
                vb.ibBack.setImageResource(R.drawable.ic_back)
                vb.ibBack.contentDescription = context.getString(R.string.close_bookmarks)
            }
        }
        vb.ibMenu.isVisible = screen is Screen.Folder
        vb.ibSearch.isVisible = screen is Screen.Folder
        vb.tvTitle.isVisible = screen !is Screen.Search
        vb.llSearch.isVisible = screen is Screen.Search
        vb.tvStatus.isVisible = screen is Screen.Search && screen.status != null
        vb.btnSave.isVisible = screen is Screen.Form
        val thisPage = (screen as? Screen.Folder)?.thisPage
        vb.flThisPage.isVisible = thisPage != null
        vb.vThisPageSeparator.isVisible = thisPage != null
        vb.rvItems.isVisible = screen !is Screen.Form
        vb.svForm.isVisible = screen is Screen.Form
        vb.tvEmpty.isVisible = screen is Screen.Folder && screen.rows.isEmpty()
        when (screen) {
            is Screen.Folder -> {
                hideKeyboard()
                if (thisPage != null) {
                    vb.ivThisPage.setImageResource(if (thisPage.bookmarked) R.drawable.ic_edit else R.drawable.ic_favorites)
                    vb.tvThisPageTitle.text = thisPage.title
                    vb.tvThisPageCaption.text = thisPage.caption
                }
                adapter.setRows(screen.rows)
                scrollToRow(focus)
            }
            is Screen.PickFolder -> {
                hideKeyboard()
                adapter.setRows(screen.rows)
                scrollToRow(focus)
            }
            is Screen.Search -> {
                vb.tvStatus.text = screen.status
                //set once: the field is not reset while it is typed in
                if (vb.etSearch.text.toString() != screen.query) {
                    vb.etSearch.setText(screen.query)
                    vb.etSearch.setSelection(vb.etSearch.length())
                }
                adapter.setRows(screen.rows)
                scrollToRow(focus)
                //the keyboard opens with the search (not when it comes back from a form)
                if (searchOpened && focus.key == BookmarksNavigator.SEARCH_FIELD) {
                    vb.etSearch.requestFocus()
                    vb.etSearch.post { showKeyboard(vb.etSearch) }
                }
            }
            is Screen.Form -> {
                vb.etName.setText(screen.name)
                vb.etName.imeOptions = if (screen.isFolder) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                vb.etUrl.setText(screen.url)
                vb.tvUrlLabel.isVisible = !screen.isFolder
                vb.etUrl.isVisible = !screen.isFolder
                vb.tvFolderLabel.isVisible = !screen.isFolder
                vb.llFolder.isVisible = !screen.isFolder
                vb.tvFolder.text = screen.folderTitle
                vb.llDelete.isVisible = screen.canDelete
                vb.svForm.scrollTo(0, 0)
                //the keyboard opens on the name when the form opens (not when it comes back from the folders)
                if (focus.key == BookmarksNavigator.FORM_NAME) {
                    vb.etName.requestFocus()
                    vb.etName.setSelection(vb.etName.length())
                    vb.etName.post { showKeyboard(vb.etName) }
                }
            }
        }
        if (firstScreen) open()
    }

    private fun open() {
        if (isOpen && backCallback.isEnabled) return
        backCallback.isEnabled = true
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        translationY = height * HIDDEN_SHIFT
        animate().alpha(1f).translationY(0f).setDuration(ANIMATION_DURATION).start()
    }

    private fun scrollToRow(focus: BookmarksNavigator.Focus) {
        val rows = adapter.rows
        var position = rows.indexOfFirst { it.key == focus.key }
        if (position == -1) position = minOf(focus.index, rows.size - 1)
        if (position > 0) {
            (vb.rvItems.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, vb.rvItems.height / 3)
        } else {
            vb.rvItems.scrollToPosition(0)
        }
    }

    private fun saveForm() {
        when (navigator.saveForm(vb.etName.text.toString(), vb.etUrl.text.toString())) {
            BookmarksNavigator.Field.NAME -> vb.etName.requestFocus()
            BookmarksNavigator.Field.URL -> vb.etUrl.requestFocus()
            null -> hideKeyboard()
        }
    }

    /** ⋮ of the header: what is not about one bookmark */
    private fun showScreenMenu() {
        val folder = screen as? Screen.Folder ?: return
        sheet.show(null, null, listOf(
            PopupMenuView.Item(R.drawable.ic_create_new_folder, context.getString(R.string.new_folder),
                enabled = folder.canCreateFolder) { navigator.createFolder() },
            PopupMenuView.Item(R.drawable.ic_export, context.getString(R.string.export_bookmarks)) { navigator.export() }
        ))
    }

    /** The actions of the row; moving it is a drag */
    private fun showRowSheet(row: Row) {
        val (title, caption) = when (row) {
            is Row.Bookmark -> {
                val url = row.item.url ?: ""
                (row.item.title?.ifEmpty { null } ?: url) to BookmarksNavigator.hostOf(url)
            }
            is Row.Folder -> row.title to context.resources.getQuantityString(R.plurals.bookmark_count, row.count, row.count)
            else -> return
        }
        val items = navigator.actionsOf(row, reorder = false).map { action ->
            PopupMenuView.Item(action.action.icon, context.getString(action.action.label), action.enabled) {
                navigator.perform(row, action.action)
            }
        }
        if (items.isNotEmpty()) sheet.show(title, caption, items)
    }

    private fun showKeyboard(view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        vb.etName.clearFocus()
        vb.etUrl.clearFocus()
        vb.etSearch.clearFocus()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        val rows = ArrayList<Row>()

        fun setRows(rows: List<Row>) {
            this.rows.clear()
            this.rows.addAll(rows)
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ViewPhoneBookmarkRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position], position)
    }

    private inner class Holder(val vb: ViewPhoneBookmarkRowBinding) : RecyclerView.ViewHolder(vb.root) {
        var row: Row? = null
            private set
        private val iconTint = ColorStateList.valueOf(context.themeColor(R.attr.tbColorDsIcon))
        private val inset = resources.getDimensionPixelSize(R.dimen.ds_phone_bookmark_row_padding)
        private val indent = resources.getDimensionPixelSize(R.dimen.ds_bookmark_folder_indent)

        init {
            vb.llMain.setOnClickListener { row?.let { navigator.onRowClick(it) } }
            vb.ibOptions.setOnClickListener { row?.let { showRowSheet(it) } }
        }

        fun bind(row: Row, position: Int) {
            this.row = row
            vb.vDivider.isVisible = position > 0
            val depth = (row as? Row.Target)?.depth ?: 0
            vb.llMain.setPaddingRelative(inset + depth * indent, vb.llMain.paddingTop, vb.llMain.paddingEnd,
                vb.llMain.paddingBottom)
            vb.llMain.isActivated = row is Row.Target && row.chosen
            bindBookmarkRow(row, BookmarkRowViews(vb.ivIcon, vb.tvTitle, vb.tvCaption, vb.ivEnd, vb.ibOptions), iconTint) { url ->
                scope.launch {
                    val favicon = FaviconsPool.get(url) ?: return@launch
                    //the holder may show another row by now
                    if (this@Holder.row?.key != row.key) return@launch
                    ImageViewCompat.setImageTintList(vb.ivIcon, null)
                    vb.ivIcon.setImageBitmap(favicon)
                }
            }
        }
    }

    companion object {
        private const val ANIMATION_DURATION = 200L
        /** The screen comes up from a little lower */
        private const val HIDDEN_SHIFT = 0.05f
    }
}
