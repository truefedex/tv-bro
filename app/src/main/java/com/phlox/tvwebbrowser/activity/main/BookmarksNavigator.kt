package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import androidx.annotation.DrawableRes
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.data.BookmarksHtml
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.HOME_PAGE
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.FavoriteItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the bookmarks screens show and do, without their look (the TV panel, the phone screen). One
 * screen at a time, on a stack that Back goes down:
 * - a folder: the row of the current page (add it, or edit its bookmark), then the folders (Home page
 *   first at the top level, while it has bookmarks) and the bookmarks;
 * - the form of a bookmark or a folder;
 * - the choice of a folder, to move a bookmark or folder to, or for the form;
 * - the search of bookmarks in every folder, over the folder it was started from.
 * All bookmarks can be exported to a bookmarks file in Downloads ([BookmarksHtml]).
 * Each change of the screen goes to [Host.render] with the row (or field) to focus.
 */
class BookmarksNavigator(
    private val context: Context,
    private val favorites: FavoritesRepository,
    private val files: Files,
    private val scope: CoroutineScope,
    private val host: Host
) {
    interface Host {
        /** The page of the current tab, null if it can not be bookmarked */
        fun currentPage(): PageInfo?
        fun render(screen: Screen, focus: Focus)
        /** Loads the url in the current tab; the screen closes */
        fun openBookmark(url: String)
        /** The screen closes */
        fun openBookmarkInNewTab(url: String)
        /** Bookmarks were added, changed, moved or deleted (the home page shows some of them) */
        fun onBookmarksChanged()
        fun showMessage(@DrawableRes icon: Int, message: String)
        /** Asks the user to confirm the [action] (a delete) */
        fun confirm(message: String, action: () -> Unit)
        /** There is nothing more to show: the form that was the first screen is saved or deleted */
        fun close()
    }

    /** What a bookmarks screen needs from its shell */
    interface ShellCallback {
        fun currentPage(): PageInfo?
        fun openBookmark(url: String)
        fun openBookmarkInNewTab(url: String)
        fun onBookmarksChanged()
        fun showMessage(@DrawableRes icon: Int, message: String)
        fun confirm(message: String, action: () -> Unit)
        /** Recognizes speech for the search field; [onResult] gets the text */
        fun startVoiceSearch(onResult: (String) -> Unit)
        val bookmarkFiles: Files
    }

    /** The bookmarks files on the device */
    interface Files {
        /**
         * Saves the [html] in Downloads as [name], or under another name if it is taken.
         * @return the name it got, null if it could not be saved
         */
        suspend fun saveToDownloads(name: String, html: String): String?
    }

    class PageInfo(val title: String, val url: String) {
        companion object {
            /** The page of the tab, null if it can not be bookmarked (none, or the built-in home page) */
            fun of(tab: BrowserTab?): PageInfo? {
                val url = tab?.url ?: return null
                if (url.isBlank() || url == Config.HOME_PAGE_URL || url == Config.HOME_URL_ALIAS) return null
                return PageInfo(tab.title, url)
            }
        }
    }

    sealed class Row(val key: String) {
        /** [item] is null for the Home page folder */
        class Folder(val id: Long, val title: String, val count: Int, val item: FavoriteItem?) : Row(folderKey(id))
        /** [folderTitle] names its folder (in search results) */
        class Bookmark(val item: FavoriteItem, val folderTitle: String? = null) : Row(bookmarkKey(item.id))
        /** A folder to choose, [depth] in the tree of folders */
        class Target(val id: Long, val title: String, val depth: Int, val chosen: Boolean) : Row("t$id")
        object NewFolder : Row("new")
    }

    /** The row of the current page in a folder */
    class ThisPage(val title: String, val caption: String, val bookmarked: Boolean)

    sealed class Screen(val title: String, val canGoBack: Boolean) {
        class Folder(title: String, canGoBack: Boolean, val id: Long, val rows: List<Row>, val thisPage: ThisPage?) :
            Screen(title, canGoBack) {
            /** Folders can not be made in the Home page folder */
            val canCreateFolder: Boolean get() = id != HOME_PAGE
        }

        class Form(title: String, val isFolder: Boolean, val name: String, val url: String, val folderTitle: String,
                   val canDelete: Boolean) : Screen(title, true)

        class PickFolder(title: String, val rows: List<Row>) : Screen(title, true)

        /** The [rows] are the bookmarks found for the [query]; [status] tells how many (null for no query) */
        class Search(title: String, val query: String, val rows: List<Row>, val status: String?) : Screen(title, true)
    }

    /**
     * Focus the row of the [key], else the row at [index]; [FORM_FOLDER] and [FORM_NAME] are the fields of the
     * form, [SEARCH_FIELD] the field of the search (focused if it is not), [SEARCH_BUTTON] the button that opens it
     */
    class Focus(val key: String?, val index: Int = 0)

    /** What can be done to a row */
    enum class Action { OPEN_IN_NEW_TAB, EDIT, RENAME, MOVE_TO_FOLDER, MOVE_UP, MOVE_DOWN, DELETE }

    class RowAction(val action: Action, val enabled: Boolean = true)

    /** A field of the form that needs a value */
    enum class Field { NAME, URL }

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

        class Search(var query: String) : Page()
    }

    private val pages = ArrayList<Page>()
    private val topPage: Page? get() = pages.lastOrNull()
    private var renderJob: Job? = null
    /** The rows shown last */
    private var rows: List<Row> = emptyList()
    /** The bookmark of the current page, edited from the row of the current page */
    private var thisPageBookmark: FavoriteItem? = null
    /** The row of the bookmark opened last: it is focused when the screen opens again */
    private var lastOpenedKey: String? = null

    private val bookmarksTitle get() = context.getString(R.string.bookmarks)
    private val homePageTitle get() = context.getString(R.string.home_page)

    /** Opens the top level, the bookmark opened last focused */
    fun start() {
        pages.clear()
        pages += Page.Folder(ROOT, bookmarksTitle)
        renderTop(lastOpenedKey)
    }

    /** Opens the form of the bookmark of the current page, Back closes the screen */
    fun startWithForm(item: FavoriteItem) {
        pages.clear()
        pages += Page.Form(item, favorites.folderOf(item), null)
        renderTop(null)
    }

    /** Drops everything shown (the screen closed) */
    fun stop() {
        renderJob?.cancel()
        pages.clear()
    }

    /** @return false on the first screen: the screen closes */
    fun back(): Boolean {
        if (pages.size <= 1) return false
        val page = pages.removeAt(pages.lastIndex)
        if (pages.isEmpty()) return false
        val focusKey = when (page) {
            is Page.Folder -> folderKey(page.id)
            is Page.Form -> if (page.item.id != 0L) keyOf(page.item) else null
            is Page.PickFolder -> if (page.form != null) FORM_FOLDER else keyOf(page.item)
            is Page.Search -> SEARCH_BUTTON
        }
        renderTop(focusKey, if (page is Page.PickFolder) page.returnIndex else 0)
        return true
    }

    private fun renderTop(focusKey: String?, focusIndex: Int = 0) {
        val page = topPage ?: return
        renderJob?.cancel()
        renderJob = scope.launch { render(page, focusKey, focusIndex) }
    }

    private suspend fun render(page: Page, focusKey: String?, focusIndex: Int) {
        when (page) {
            is Page.Folder -> {
                rows = folderRows(page.id)
                host.render(Screen.Folder(page.title, pages.size > 1, page.id, rows, loadThisPage(page)),
                    Focus(focusKey, focusIndex))
            }
            is Page.Form -> {
                val item = page.item
                val title = context.getString(when {
                    !item.isFolder -> R.string.edit_bookmark
                    item.id == 0L -> R.string.new_folder
                    else -> R.string.rename_folder
                })
                host.render(Screen.Form(title, item.isFolder, item.title ?: "", item.url ?: "",
                    folderTitle(page.folder), item.id != 0L), Focus(focusKey ?: FORM_NAME))
            }
            is Page.PickFolder -> {
                val chosen = page.form?.folder ?: favorites.folderOf(page.item)
                rows = pickerRows(page.item, chosen)
                val title = context.getString(R.string.move_item_to, page.item.title ?: page.item.url ?: "")
                host.render(Screen.PickFolder(title, rows), Focus(focusKey ?: "t$chosen", focusIndex))
            }
            is Page.Search -> {
                rows = searchRows(page.query)
                val status = when {
                    page.query.isBlank() -> null
                    rows.isEmpty() -> context.getString(R.string.no_bookmarks_found)
                    else -> context.resources.getQuantityString(R.plurals.bookmarks_found, rows.size, rows.size)
                }
                host.render(Screen.Search(context.getString(R.string.search_bookmarks), page.query, rows, status),
                    Focus(focusKey, focusIndex))
            }
        }
    }

    // --- a folder

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

    private suspend fun loadThisPage(page: Page.Folder): ThisPage? {
        val info = host.currentPage() ?: return null
        val bookmark = favorites.findByUrl(info.url)
        thisPageBookmark = bookmark
        return if (bookmark != null) {
            ThisPage(context.getString(R.string.edit_bookmark_of_this_page),
                context.getString(R.string.bookmarked_host, hostOf(info.url)), true)
        } else {
            ThisPage(if (page.id == ROOT) context.getString(R.string.add_this_page) else
                context.getString(R.string.add_this_page_to, page.title),
                context.getString(R.string.page_title_and_host, info.title.ifEmpty { info.url }, hostOf(info.url)), false)
        }
    }

    /** Adds the current page to the folder shown, or opens the form of its bookmark */
    fun onThisPageClick() {
        val page = topPage as? Page.Folder ?: return
        thisPageBookmark?.let {
            openForm(it)
            return
        }
        val info = host.currentPage() ?: return
        renderJob?.cancel()
        renderJob = scope.launch {
            val item = addPage(info, page.id)
            render(page, keyOf(item), 0)
        }
    }

    /** Adds the page to the [folder] */
    suspend fun addPage(info: PageInfo, folder: Long = ROOT): FavoriteItem {
        val item = FavoriteItem().apply {
            title = info.title.ifEmpty { info.url }
            url = info.url
        }
        favorites.add(item, folder)
        host.onBookmarksChanged()
        host.showMessage(R.drawable.ic_favorites, context.getString(R.string.bookmark_added))
        return item
    }

    fun onRowClick(row: Row) {
        when (row) {
            is Row.Folder -> {
                pages += Page.Folder(row.id, row.title)
                renderTop(null)
            }
            is Row.Bookmark -> {
                val url = row.item.url ?: return
                lastOpenedKey = row.key
                host.openBookmark(url)
            }
            is Row.Target -> onFolderChosen(row.id, row.title)
            Row.NewFolder -> openForm(FavoriteItem(), ROOT) { folder -> onFolderChosen(folder.id, folder.title ?: "") }
        }
    }

    /** Opens the form of a new folder in the folder shown */
    fun createFolder() {
        val page = topPage as? Page.Folder ?: return
        if (page.id == HOME_PAGE) return
        openForm(FavoriteItem(), page.id)
    }

    // --- export

    /** Writes every bookmark and folder into a bookmarks file in Downloads; a message names the file */
    fun export() {
        scope.launch {
            val html = BookmarksHtml.write(favorites.exportTree(homePageTitle))
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val name = files.saveToDownloads(context.getString(R.string.bookmarks_file_name, date), html)
            host.showMessage(R.drawable.ic_export, if (name != null) context.getString(R.string.bookmarks_exported, name)
                else context.getString(R.string.bookmarks_not_exported))
        }
    }

    // --- the search

    /** Opens the search over the folder shown, with the [query] typed so far */
    fun startSearch(query: String = "") {
        if (topPage !is Page.Folder) return
        pages += Page.Search(query)
        renderTop(SEARCH_FIELD)
    }

    /** The results follow the [query] as it is typed; the field keeps the focus */
    fun search(query: String) {
        val page = topPage as? Page.Search ?: return
        if (page.query == query) return
        page.query = query
        renderTop(SEARCH_FIELD)
    }

    /** The bookmarks found, each with the name of its folder */
    private suspend fun searchRows(query: String): List<Row> {
        val found = favorites.search(query)
        if (found.isEmpty()) return emptyList()
        val folderTitles = favorites.folders().associate { it.id to (it.title ?: "") }
        return found.map { item ->
            val title = when (val folder = favorites.folderOf(item)) {
                ROOT -> bookmarksTitle
                HOME_PAGE -> homePageTitle
                else -> folderTitles[folder] ?: bookmarksTitle
            }
            Row.Bookmark(item, title)
        }
    }

    /**
     * What can be done to the row; the Home page folder has nothing.
     * @param reorder whether the actions include moving up and down (else the row is dragged); search
     *   results are not in a folder's order, so they have no moves
     */
    fun actionsOf(row: Row, reorder: Boolean = true): List<RowAction> {
        val siblings = rows.filter { sameKind(it, row) }
        val place = siblings.indexOf(row)
        val moves = if (reorder && topPage !is Page.Search) listOf(RowAction(Action.MOVE_UP, place > 0),
            RowAction(Action.MOVE_DOWN, place < siblings.size - 1)) else emptyList()
        return when (row) {
            is Row.Bookmark -> listOf(RowAction(Action.OPEN_IN_NEW_TAB), RowAction(Action.EDIT),
                RowAction(Action.MOVE_TO_FOLDER)) + moves + RowAction(Action.DELETE)
            is Row.Folder -> if (row.item == null) emptyList() else
                listOf(RowAction(Action.RENAME), RowAction(Action.MOVE_TO_FOLDER)) + moves + RowAction(Action.DELETE)
            else -> emptyList()
        }
    }

    /** Whether the rows can trade places (bookmarks with bookmarks, folders with folders, not Home page) */
    fun sameKind(a: Row, b: Row): Boolean = when (a) {
        is Row.Bookmark -> b is Row.Bookmark
        is Row.Folder -> a.item != null && b is Row.Folder && b.item != null
        else -> false
    }

    fun perform(row: Row, action: Action) {
        val item = when (row) {
            is Row.Bookmark -> row.item
            is Row.Folder -> row.item ?: return
            else -> return
        }
        when (action) {
            Action.OPEN_IN_NEW_TAB -> item.url?.let { host.openBookmarkInNewTab(it) }
            Action.EDIT, Action.RENAME -> openForm(item)
            Action.MOVE_TO_FOLDER -> openPicker(item, null, rows.indexOf(row).coerceAtLeast(0))
            Action.MOVE_UP -> move(item, -1)
            Action.MOVE_DOWN -> move(item, 1)
            Action.DELETE -> delete(item)
        }
    }

    /** Moves the row by [offset] places among the rows of its kind (a drag) */
    fun move(row: Row, offset: Int) {
        when (row) {
            is Row.Bookmark -> move(row.item, offset)
            is Row.Folder -> row.item?.let { move(it, offset) }
            else -> {}
        }
    }

    private fun move(item: FavoriteItem, offset: Int) {
        renderJob?.cancel()
        renderJob = scope.launch {
            if (favorites.move(item, offset)) {
                host.onBookmarksChanged()
            }
            topPage?.let { render(it, keyOf(item), 0) }
        }
    }

    /** A folder with something in it is deleted after the user confirms it */
    private fun delete(item: FavoriteItem) {
        scope.launch {
            if (item.isFolder && favorites.children(item.id).isNotEmpty()) {
                host.confirm(context.getString(R.string.delete_folder_with_bookmarks, item.title ?: "")) {
                    scope.launch { doDelete(item) }
                }
            } else {
                doDelete(item)
            }
        }
    }

    /** The focus goes to the row that takes the place of the deleted one */
    private suspend fun doDelete(item: FavoriteItem) {
        (topPage as? Page.Form)?.let { pages.remove(it) }
        val index = rows.indexOfFirst { it.key == keyOf(item) }.coerceAtLeast(0)
        favorites.delete(item)
        host.onBookmarksChanged()
        renderJob?.cancel()
        val page = topPage
        if (page == null) {
            //the form was the first screen
            host.close()
            return
        }
        renderJob = scope.launch { render(page, null, index) }
    }

    // --- the form

    private fun openForm(item: FavoriteItem, folder: Long = favorites.folderOf(item),
                         onSaved: (suspend (FavoriteItem) -> Unit)? = null) {
        pages += Page.Form(item, folder, onSaved)
        renderTop(null)
    }

    /** Opens the choice of the folder of the form */
    fun chooseFormFolder() {
        val form = topPage as? Page.Form ?: return
        openPicker(form.item, form, 0)
    }

    fun deleteFormItem() {
        val form = topPage as? Page.Form ?: return
        delete(form.item)
    }

    /**
     * Saves the form, then shows the screen under it.
     * @return the field that needs a value, null if the form was saved
     */
    fun saveForm(name: String, url: String): Field? {
        val page = topPage as? Page.Form ?: return null
        val item = page.item
        val trimmedName = name.trim()
        if (item.isFolder) {
            if (trimmedName.isEmpty()) return Field.NAME
            item.title = trimmedName
        } else {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) return Field.URL
            item.url = trimmedUrl
            item.title = trimmedName.ifEmpty { trimmedUrl }
        }
        renderJob?.cancel()
        renderJob = scope.launch {
            if (item.id == 0L) favorites.add(item, page.folder) else favorites.moveTo(item, page.folder)
            host.onBookmarksChanged()
            pages.remove(page)
            val onSaved = page.onSaved
            when {
                onSaved != null -> onSaved(item)
                //the form was the first screen
                pages.isEmpty() -> host.close()
                else -> render(pages.last(), keyOf(item), 0)
            }
        }
        return null
    }

    // --- the choice of a folder

    private fun openPicker(item: FavoriteItem, form: Page.Form?, returnIndex: Int) {
        pages += Page.PickFolder(item, form, returnIndex)
        renderTop(null)
    }

    /** The folders as a tree: the top level, Home page (not for folders), then the folders but the item's own */
    private suspend fun pickerRows(item: FavoriteItem, chosen: Long): List<Row> {
        val byParent = favorites.folders().groupBy { it.parent ?: ROOT }
        val rows = ArrayList<Row>()
        rows += Row.Target(ROOT, bookmarksTitle, 0, chosen == ROOT)
        if (!item.isFolder) {
            rows += Row.Target(HOME_PAGE, homePageTitle, 1, chosen == HOME_PAGE)
        }
        fun addFolders(parent: Long, depth: Int) {
            val folders = byParent[parent] ?: return
            for (folder in folders.sortedWith(compareBy<FavoriteItem> { it.order }.thenByDescending { it.id })) {
                if (folder.id == item.id) continue
                rows += Row.Target(folder.id, folder.title ?: "", depth, chosen == folder.id)
                addFolders(folder.id, depth + 1)
            }
        }
        addFolders(ROOT, 1)
        rows += Row.NewFolder
        return rows
    }

    private fun onFolderChosen(id: Long, title: String) {
        val page = topPage as? Page.PickFolder ?: return
        pages.remove(page)
        val form = page.form
        if (form != null) {
            form.folder = id
            renderTop(FORM_FOLDER)
            return
        }
        renderJob?.cancel()
        renderJob = scope.launch {
            if (favorites.folderOf(page.item) != id && favorites.moveTo(page.item, id)) {
                host.onBookmarksChanged()
                host.showMessage(R.drawable.ic_move_to_folder, context.getString(R.string.moved_to_folder, title))
            }
            topPage?.let { render(it, keyOf(page.item), page.returnIndex) }
        }
    }

    private suspend fun folderTitle(id: Long): String = when (id) {
        ROOT -> bookmarksTitle
        HOME_PAGE -> homePageTitle
        else -> favorites.get(id)?.title ?: bookmarksTitle
    }

    companion object {
        /** The folder field of the form, focused when a folder was chosen for it */
        const val FORM_FOLDER = "folder"
        /** The name field of the form */
        const val FORM_NAME = "name"
        /** The field of the search */
        const val SEARCH_FIELD = "search"
        /** The button that opens the search, focused when the search is closed */
        const val SEARCH_BUTTON = "searchButton"

        private fun folderKey(id: Long) = "f$id"
        private fun bookmarkKey(id: Long) = "b$id"
        private fun keyOf(item: FavoriteItem) = if (item.isFolder) folderKey(item.id) else bookmarkKey(item.id)

        /** The host of the url without its scheme, as the bookmarks show it */
        fun hostOf(url: String): String = android.net.Uri.parse(url).host?.ifEmpty { null } ?: url
    }
}
