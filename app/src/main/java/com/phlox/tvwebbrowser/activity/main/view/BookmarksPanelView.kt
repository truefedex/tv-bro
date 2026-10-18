package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.HOME_PAGE
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT
import com.phlox.tvwebbrowser.databinding.ViewBookmarkRowBinding
import com.phlox.tvwebbrowser.databinding.ViewBookmarksPanelBinding
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The TV bookmarks panel, under the star of the bar, over the page. It shows one page at a time:
 * - a folder: the row of the current page (Add this page, or Edit bookmark for this page), then the
 *   folders (Home page first, while it has bookmarks) and the bookmarks. OK opens a bookmark in the
 *   current tab or a folder in the panel; the button at the end of a row opens its options.
 * - the form of a bookmark or a folder;
 * - the choice of a folder, to move a bookmark or folder to, or for the form.
 * Back goes back a page, and closes the panel on the first one; the star gets the focus back.
 */
class BookmarksPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AnchoredOverlayView(context, attrs) {
    interface Callback {
        /** The page of the current tab, null if it can not be bookmarked */
        fun currentPage(): PageInfo?
        /** Loads the url in the current tab and hides the bar */
        fun openBookmark(url: String)
        fun openBookmarkInNewTab(url: String)
        /** Bookmarks were added, changed, moved or deleted (the home page shows some of them) */
        fun onBookmarksChanged()
        fun showMessage(@DrawableRes icon: Int, message: String)
        /** Asks the user to confirm the [action] (a delete) */
        fun confirm(message: String, action: () -> Unit)
    }

    class PageInfo(val title: String, val url: String)

    private sealed class Page {
        class Folder(val id: Long, val title: String) : Page()

        /**
         * The form of the item, a new one if its id is 0. It is saved to the [folder]; [onSaved] (after the
         * form is closed) replaces going back to the page under it.
         */
        class Form(val item: FavoriteItem, var folder: Long, val onSaved: (suspend (FavoriteItem) -> Unit)?) : Page()

        /**
         * The choice of a folder for the item: it moves there, or the folder goes to the [form].
         * @param returnIndex the place of the item's row, focused when the item has moved away
         */
        class PickFolder(val item: FavoriteItem, val form: Form?, val returnIndex: Int) : Page()
    }

    private sealed class Row(val key: String) {
        /** [item] is null for the Home page folder */
        class Folder(val id: Long, val title: String, val count: Int, val item: FavoriteItem?) : Row(folderKey(id))
        class Bookmark(val item: FavoriteItem) : Row(bookmarkKey(item.id))
        class Target(val id: Long, val title: String, val depth: Int, val chosen: Boolean) : Row("t$id")
        object NewFolder : Row("new")
    }

    var callback: Callback? = null
    private lateinit var scope: CoroutineScope
    private lateinit var menu: PopupMenuView
    private val favorites get() = TVBro.container.data.favorites

    private val vb = ViewBookmarksPanelBinding.inflate(LayoutInflater.from(context), this)
    override val panel: View get() = vb.llPanel
    private val adapter = Adapter()
    private val pages = ArrayList<Page>()
    private val topPage: Page get() = pages.last()
    private var renderJob: Job? = null
    /** The bookmark of the current page, edited from the row of the current page */
    private var thisPageBookmark: FavoriteItem? = null
    /** The row of the bookmark opened last: it is focused when the panel opens again */
    private var lastOpenedKey: String? = null

    private val bookmarksTitle get() = context.getString(R.string.bookmarks)
    private val homePageTitle get() = context.getString(R.string.home_page)

    init {
        vb.llPanel.clipToOutline = true
        vb.rvItems.layoutManager = LinearLayoutManager(context)
        vb.rvItems.itemAnimator = null
        vb.rvItems.adapter = adapter
        vb.ibBack.setOnClickListener { onBack() }
        vb.ibMenu.setOnClickListener { showPanelMenu() }
        vb.llThisPage.setOnClickListener { onThisPageClick() }
        vb.llFolder.setOnClickListener {
            val form = topPage as? Page.Form ?: return@setOnClickListener
            openPicker(form.item, form, 0)
        }
        vb.btnSave.setOnClickListener { saveForm() }
        vb.btnCancel.setOnClickListener { onBack() }
        vb.btnDelete.setOnClickListener {
            val form = topPage as? Page.Form ?: return@setOnClickListener
            delete(form.item)
        }
        val saveOnDone = TextView.OnEditorActionListener { view, actionId, _ ->
            val form = topPage as? Page.Form
            val last = view == vb.etUrl || form?.item?.isFolder == true
            if (last && (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT)) {
                saveForm()
                true
            } else false
        }
        vb.etName.setOnEditorActionListener(saveOnDone)
        vb.etUrl.setOnEditorActionListener(saveOnDone)
    }

    fun attach(scope: CoroutineScope, menu: PopupMenuView, callback: Callback) {
        this.scope = scope
        this.menu = menu
        this.callback = callback
    }

    /**
     * Opens the panel under the [anchor] (the star), its end under the end of [endAlignTo], with the
     * top level of the bookmarks; the bookmark opened last is focused, else the first row.
     */
    fun show(anchor: View, endAlignTo: View) {
        if (isOpen) return
        pages.clear()
        pages += Page.Folder(ROOT, bookmarksTitle)
        renderJob?.cancel()
        renderJob = scope.launch {
            val rows = folderRows(ROOT)
            applyFolder(topPage as Page.Folder, rows, loadThisPage())
            anchor.isActivated = true
            showUnder(anchor, resources.getDimensionPixelSize(R.dimen.ds_site_panel_gap),
                if (rows.isEmpty()) fallbackFocus() else vb.rvItems, endAlignTo,
                resources.getDimensionPixelSize(R.dimen.ds_bookmarks_panel_margin_bottom))
            focusRow(lastOpenedKey, 0)
        }
    }

    override fun onBack() {
        if (pages.size <= 1) {
            hide()
            return
        }
        hideKeyboard()
        val page = pages.removeAt(pages.lastIndex)
        val focusKey = when (page) {
            is Page.Folder -> folderKey(page.id)
            is Page.Form -> if (page.item.id != 0L) keyOf(page.item) else null
            is Page.PickFolder -> if (page.form != null) FORM_FOLDER_KEY else keyOf(page.item)
        }
        renderTop(focusKey, if (page is Page.PickFolder) page.returnIndex else 0)
    }

    override fun onHidden() {
        menu.hide()
        hideKeyboard()
        renderJob?.cancel()
        pages.clear()
        anchor?.isActivated = false
    }

    private fun renderTop(focusKey: String?, focusIndex: Int = 0) {
        when (val page = topPage) {
            is Page.Folder -> renderFolder(page, focusKey, focusIndex)
            is Page.Form -> renderForm(page, focusKey == FORM_FOLDER_KEY)
            is Page.PickFolder -> renderPicker(page, focusKey)
        }
    }

    // --- a folder

    private fun renderFolder(page: Page.Folder, focusKey: String?, focusIndex: Int) {
        renderJob?.cancel()
        renderJob = scope.launch {
            applyFolder(page, folderRows(page.id), loadThisPage())
            focusRow(focusKey, focusIndex)
        }
    }

    private suspend fun folderRows(id: Long): List<Row> {
        val rows = ArrayList<Row>()
        if (id == ROOT) {
            val homePageCount = favorites.countBookmarks(HOME_PAGE)
            if (homePageCount > 0) rows += Row.Folder(HOME_PAGE, homePageTitle, homePageCount, null)
        }
        for (item in favorites.children(id)) {
            rows += if (item.isFolder) {
                Row.Folder(item.id, item.title ?: "", favorites.countBookmarks(item.id), item)
            } else {
                Row.Bookmark(item)
            }
        }
        return rows
    }

    private suspend fun loadThisPage(): Pair<PageInfo, FavoriteItem?>? {
        val page = callback?.currentPage() ?: return null
        return page to favorites.findByUrl(page.url)
    }

    private fun applyFolder(page: Page.Folder, rows: List<Row>, thisPage: Pair<PageInfo, FavoriteItem?>?) {
        showSections(list = true, thisPage = thisPage != null, form = false)
        vb.ibBack.isVisible = pages.size > 1
        vb.ibMenu.isVisible = true
        vb.tvTitle.text = page.title
        thisPageBookmark = thisPage?.second
        if (thisPage != null) {
            val (info, bookmark) = thisPage
            if (bookmark != null) {
                vb.ivThisPage.setImageResource(R.drawable.ic_edit)
                vb.tvThisPageTitle.setText(R.string.edit_bookmark_of_this_page)
                vb.tvThisPageCaption.text = context.getString(R.string.bookmarked_host, hostOf(info.url))
            } else {
                vb.ivThisPage.setImageResource(R.drawable.ic_favorites)
                vb.tvThisPageTitle.text = if (page.id == ROOT) context.getString(R.string.add_this_page) else
                    context.getString(R.string.add_this_page_to, page.title)
                vb.tvThisPageCaption.text = context.getString(R.string.page_title_and_host,
                    info.title.ifEmpty { info.url }, hostOf(info.url))
            }
        }
        vb.tvEmpty.isVisible = rows.isEmpty()
        adapter.rows = rows
    }

    private fun onThisPageClick() {
        val page = topPage as? Page.Folder ?: return
        thisPageBookmark?.let {
            openForm(it)
            return
        }
        val info = callback?.currentPage() ?: return
        renderJob?.cancel()
        renderJob = scope.launch {
            val item = FavoriteItem().apply {
                title = info.title.ifEmpty { info.url }
                url = info.url
            }
            favorites.add(item, page.id)
            callback?.onBookmarksChanged()
            callback?.showMessage(R.drawable.ic_favorites, context.getString(R.string.bookmark_added))
            applyFolder(page, folderRows(page.id), loadThisPage())
            focusRow(keyOf(item), 0)
        }
    }

    private fun onRowClick(row: Row) {
        when (row) {
            is Row.Folder -> {
                pages += Page.Folder(row.id, row.title)
                renderTop(null)
            }
            is Row.Bookmark -> {
                val url = row.item.url ?: return
                lastOpenedKey = row.key
                hide()
                callback?.openBookmark(url)
            }
            is Row.Target -> onFolderChosen(row.id, row.title)
            Row.NewFolder -> openForm(FavoriteItem(), ROOT) { folder -> onFolderChosen(folder.id, folder.title ?: "") }
        }
    }

    /** The ⋮ of the header: what is not about one bookmark */
    private fun showPanelMenu() {
        val page = topPage as? Page.Folder ?: return
        val items = listOf(
            PopupMenuView.Item(R.drawable.ic_create_new_folder, context.getString(R.string.new_folder),
                enabled = page.id != HOME_PAGE) { openForm(FavoriteItem(), page.id) }
        )
        menu.show(vb.ibMenu, items, alignEnd = true)
    }

    private fun showRowMenu(row: Row, anchor: View) {
        val index = adapter.rows.indexOf(row)
        val items = when (row) {
            is Row.Bookmark -> {
                val item = row.item
                val siblings = adapter.rows.filterIsInstance<Row.Bookmark>()
                val place = siblings.indexOf(row)
                listOf(
                    PopupMenuView.Item(R.drawable.ic_open_in_new, context.getString(R.string.open_in_new_tab)) {
                        item.url?.let { url ->
                            hide()
                            callback?.openBookmarkInNewTab(url)
                        }
                    },
                    PopupMenuView.Item(R.drawable.ic_edit, context.getString(R.string.edit)) { openForm(item) },
                    PopupMenuView.Item(R.drawable.ic_move_to_folder, context.getString(R.string.move_to_folder)) {
                        openPicker(item, null, index)
                    },
                    PopupMenuView.Item(R.drawable.ic_arrow_up, context.getString(R.string.move_up), place > 0) { move(item, -1) },
                    PopupMenuView.Item(R.drawable.ic_arrow_down, context.getString(R.string.move_down),
                        place < siblings.size - 1) { move(item, 1) },
                    PopupMenuView.Item(R.drawable.ic_delete, context.getString(R.string.delete)) { delete(item) }
                )
            }
            is Row.Folder -> {
                val item = row.item ?: return
                val siblings = adapter.rows.filter { it is Row.Folder && it.item != null }
                val place = siblings.indexOf(row)
                listOf(
                    PopupMenuView.Item(R.drawable.ic_edit, context.getString(R.string.rename)) { openForm(item) },
                    PopupMenuView.Item(R.drawable.ic_move_to_folder, context.getString(R.string.move_to_folder)) {
                        openPicker(item, null, index)
                    },
                    PopupMenuView.Item(R.drawable.ic_arrow_up, context.getString(R.string.move_up), place > 0) { move(item, -1) },
                    PopupMenuView.Item(R.drawable.ic_arrow_down, context.getString(R.string.move_down),
                        place < siblings.size - 1) { move(item, 1) },
                    PopupMenuView.Item(R.drawable.ic_delete, context.getString(R.string.delete)) { delete(item) }
                )
            }
            else -> return
        }
        menu.show(anchor, items, alignEnd = true)
    }

    private fun move(item: FavoriteItem, offset: Int) {
        renderJob?.cancel()
        renderJob = scope.launch {
            if (favorites.move(item, offset)) {
                callback?.onBookmarksChanged()
            }
            renderTop(keyOf(item))
        }
    }

    /** A folder with something in it is deleted after the user confirms it */
    private fun delete(item: FavoriteItem) {
        scope.launch {
            if (item.isFolder && favorites.children(item.id).isNotEmpty()) {
                callback?.confirm(context.getString(R.string.delete_folder_with_bookmarks, item.title ?: "")) {
                    scope.launch { doDelete(item) }
                }
            } else {
                doDelete(item)
            }
        }
    }

    /** The focus goes to the row that takes the place of the deleted one */
    private suspend fun doDelete(item: FavoriteItem) {
        val form = topPage as? Page.Form
        if (form != null) {
            hideKeyboard()
            pages.remove(form)
        }
        val index = adapter.rows.indexOfFirst { it.key == keyOf(item) }.coerceAtLeast(0)
        favorites.delete(item)
        callback?.onBookmarksChanged()
        renderTop(null, index)
    }

    // --- the form

    private fun openForm(item: FavoriteItem, folder: Long = favorites.folderOf(item),
                         onSaved: (suspend (FavoriteItem) -> Unit)? = null) {
        pages += Page.Form(item, folder, onSaved)
        renderTop(null)
    }

    private fun renderForm(page: Page.Form, focusFolder: Boolean) {
        val item = page.item
        showSections(list = false, thisPage = false, form = true)
        vb.ibBack.isVisible = true
        vb.ibMenu.isVisible = false
        vb.tvTitle.setText(when {
            !item.isFolder -> R.string.edit_bookmark
            item.id == 0L -> R.string.new_folder
            else -> R.string.rename_folder
        })
        vb.etName.setText(item.title ?: "")
        vb.etName.imeOptions = if (item.isFolder) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        vb.etUrl.setText(item.url ?: "")
        vb.tvUrlLabel.isVisible = !item.isFolder
        vb.etUrl.isVisible = !item.isFolder
        vb.tvFolderLabel.isVisible = !item.isFolder
        vb.llFolder.isVisible = !item.isFolder
        vb.btnDelete.isVisible = item.id != 0L
        renderJob?.cancel()
        renderJob = scope.launch { vb.tvFolder.text = folderTitle(page.folder) }
        if (focusFolder) {
            vb.llFolder.requestFocus()
        } else {
            vb.etName.requestFocus()
            vb.etName.setSelection(vb.etName.length())
        }
    }

    private fun saveForm() {
        val page = topPage as? Page.Form ?: return
        val item = page.item
        val name = vb.etName.text.toString().trim()
        if (item.isFolder) {
            if (name.isEmpty()) {
                vb.etName.requestFocus()
                return
            }
            item.title = name
        } else {
            val url = vb.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                vb.etUrl.requestFocus()
                return
            }
            item.url = url
            item.title = name.ifEmpty { url }
        }
        hideKeyboard()
        renderJob?.cancel()
        renderJob = scope.launch {
            if (item.id == 0L) favorites.add(item, page.folder) else favorites.moveTo(item, page.folder)
            callback?.onBookmarksChanged()
            pages.remove(page)
            val onSaved = page.onSaved
            if (onSaved != null) onSaved(item) else renderTop(keyOf(item))
        }
    }

    // --- the choice of a folder

    private fun openPicker(item: FavoriteItem, form: Page.Form?, returnIndex: Int) {
        pages += Page.PickFolder(item, form, returnIndex)
        renderTop(null)
    }

    /** The folders as a tree: the top level, Home page (not for folders), then the folders but the item's own */
    private fun renderPicker(page: Page.PickFolder, focusKey: String?) {
        renderJob?.cancel()
        renderJob = scope.launch {
            val chosen = page.form?.folder ?: favorites.folderOf(page.item)
            val byParent = favorites.folders().groupBy { it.parent ?: ROOT }
            val rows = ArrayList<Row>()
            rows += Row.Target(ROOT, bookmarksTitle, 0, chosen == ROOT)
            if (!page.item.isFolder) {
                rows += Row.Target(HOME_PAGE, homePageTitle, 1, chosen == HOME_PAGE)
            }
            fun addFolders(parent: Long, depth: Int) {
                val folders = byParent[parent] ?: return
                for (folder in folders.sortedWith(compareBy<FavoriteItem> { it.order }.thenByDescending { it.id })) {
                    if (folder.id == page.item.id) continue
                    rows += Row.Target(folder.id, folder.title ?: "", depth, chosen == folder.id)
                    addFolders(folder.id, depth + 1)
                }
            }
            addFolders(ROOT, 1)
            rows += Row.NewFolder

            showSections(list = true, thisPage = false, form = false)
            vb.ibBack.isVisible = true
            vb.ibMenu.isVisible = false
            vb.tvTitle.text = context.getString(R.string.move_item_to, page.item.title ?: page.item.url ?: "")
            vb.tvEmpty.isVisible = false
            adapter.rows = rows
            focusRow(focusKey ?: "t$chosen", 0)
        }
    }

    private fun onFolderChosen(id: Long, title: String) {
        val page = topPage as? Page.PickFolder ?: return
        pages.remove(page)
        val form = page.form
        if (form != null) {
            form.folder = id
            renderTop(FORM_FOLDER_KEY)
            return
        }
        renderJob?.cancel()
        renderJob = scope.launch {
            if (favorites.folderOf(page.item) != id && favorites.moveTo(page.item, id)) {
                callback?.onBookmarksChanged()
                callback?.showMessage(R.drawable.ic_move_to_folder, context.getString(R.string.moved_to_folder, title))
            }
            renderTop(keyOf(page.item), page.returnIndex)
        }
    }

    private suspend fun folderTitle(id: Long): String = when (id) {
        ROOT -> bookmarksTitle
        HOME_PAGE -> homePageTitle
        else -> favorites.get(id)?.title ?: bookmarksTitle
    }

    // --- views

    private fun showSections(list: Boolean, thisPage: Boolean, form: Boolean) {
        vb.llThisPage.isVisible = thisPage
        vb.vThisPageSeparator.isVisible = thisPage
        vb.rvItems.isVisible = list
        if (!list) vb.tvEmpty.isVisible = false
        vb.llForm.isVisible = form
    }

    /** What gets the focus when the list has no rows */
    private fun fallbackFocus(): View = when {
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
            vb.llMain.setOnClickListener { row?.let { onRowClick(it) } }
            vb.ibOptions.setOnClickListener { row?.let { showRowMenu(it, vb.ibOptions) } }
        }

        fun bind(row: Row, position: Int) {
            this.row = row
            vb.vDivider.isVisible = position > 0
            val depth = (row as? Row.Target)?.depth ?: 0
            vb.llMain.setPaddingRelative(inset + depth * indent, vb.llMain.paddingTop, vb.llMain.paddingEnd,
                vb.llMain.paddingBottom)
            vb.llMain.isActivated = row is Row.Target && row.chosen
            when (row) {
                is Row.Folder -> {
                    setIcon(if (row.item == null) R.drawable.ic_home else R.drawable.ic_folder)
                    setTexts(row.title, context.resources.getQuantityString(R.plurals.bookmark_count, row.count, row.count))
                    setEnd(R.drawable.ic_chevron_right)
                    setOptions(row.item != null, row.title)
                }
                is Row.Bookmark -> {
                    val url = row.item.url ?: ""
                    val title = row.item.title?.ifEmpty { null } ?: url
                    setTexts(title, hostOf(url))
                    setEnd(0)
                    setOptions(true, title)
                    setIcon(R.drawable.ic_not_available)
                    loadFavicon(row, url)
                }
                is Row.Target -> {
                    setIcon(when (row.id) {
                        ROOT -> R.drawable.ic_favorites
                        HOME_PAGE -> R.drawable.ic_home
                        else -> R.drawable.ic_folder
                    })
                    setTexts(row.title, null)
                    setEnd(if (row.chosen) R.drawable.ic_check else 0)
                    setOptions(false, row.title)
                }
                Row.NewFolder -> {
                    setIcon(R.drawable.ic_create_new_folder)
                    setTexts(context.getString(R.string.new_folder), null)
                    setEnd(0)
                    setOptions(false, "")
                }
            }
        }

        private fun setIcon(@DrawableRes icon: Int) {
            ImageViewCompat.setImageTintList(vb.ivIcon, iconTint)
            vb.ivIcon.setImageResource(icon)
        }

        private fun setTexts(title: String, caption: String?) {
            vb.tvTitle.text = title
            vb.tvCaption.text = caption
            vb.tvCaption.isVisible = caption != null
        }

        private fun setEnd(@DrawableRes icon: Int) {
            vb.ivEnd.isVisible = icon != 0
            if (icon != 0) vb.ivEnd.setImageResource(icon)
        }

        private fun setOptions(visible: Boolean, title: String) {
            vb.ibOptions.isVisible = visible
            vb.ibOptions.contentDescription = context.getString(R.string.options_of, title)
        }

        private fun loadFavicon(row: Row.Bookmark, url: String) {
            if (url.isEmpty() || url == Config.HOME_PAGE_URL) return
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
        /** The folder field of the form, focused when a folder was chosen for it */
        private const val FORM_FOLDER_KEY = "folder"
        private const val ROW_FOCUS_ATTEMPTS = 3

        private fun folderKey(id: Long) = "f$id"
        private fun bookmarkKey(id: Long) = "b$id"
        private fun keyOf(item: FavoriteItem) = if (item.isFolder) folderKey(item.id) else bookmarkKey(item.id)

        private fun hostOf(url: String): String = Uri.parse(url).host?.ifEmpty { null } ?: url
    }
}
