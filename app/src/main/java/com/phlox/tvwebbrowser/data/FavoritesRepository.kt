package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.dao.FavoritesDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Bookmarks: the user's ones and the ones of the built-in home page.
 *
 * Bookmarks are kept in folders (items without a url, [FavoriteItem.parent] of their contents), [ROOT] is
 * the top level. The bookmarks of the built-in home page make the [HOME_PAGE] folder: it has no row in the
 * table, its bookmarks are marked with [FavoriteItem.homePageBookmark] and hold no folders. In a folder,
 * folders come first, then bookmarks, each in their [FavoriteItem.order]: new ones go to the top, while
 * new home page bookmarks go to the end (the home page shows the first of them).
 */
class FavoritesRepository(private val dao: FavoritesDao) {
    companion object {
        /** The top level */
        const val ROOT = 0L
        /** The folder of the home page bookmarks */
        const val HOME_PAGE = -1L
    }

    /** How the bookmarks of a file are added to the user's ones */
    enum class ImportMode {
        /**
         * Into a new folder at the top level, keeping the folders of the file (its Home page folder becomes a
         * plain folder there); addresses that are saved already are skipped, and folders that would stay
         * empty are not made
         */
        NEW_FOLDER,
        /**
         * Folders with the same name (ignoring case) in the same place are joined, the Home page folder of the
         * file joins [HOME_PAGE] (its folders, which [HOME_PAGE] can not hold, join the top level); a bookmark
         * whose address is saved already gets the title and the folder of the file's one
         */
        MERGE
    }

    /**
     * @param skipped bookmarks of the file left out: their address is saved already (or comes again in
     *   the file), and in [ImportMode.MERGE] also those that are saved already with the same title and folder
     * @param folder the folder made by [ImportMode.NEW_FOLDER], null if nothing was added
     */
    class ImportResult(val added: Int, val updated: Int, val skipped: Int, val folder: FavoriteItem?)

    suspend fun all(): List<FavoriteItem> = dao.getAll()

    suspend fun homePageBookmarks(): List<FavoriteItem> = dao.getHomePageBookmarks()

    /** Bookmarks for the TV home screen channel: user bookmarks first, then Home page ones, without duplicate urls */
    suspend fun channelBookmarks(): List<FavoriteItem> = orderForChannel(dao.getBookmarks())

    fun observeChannelBookmarks(): Flow<List<FavoriteItem>> = dao.observeBookmarks().map(::orderForChannel)

    private fun orderForChannel(items: List<FavoriteItem>): List<FavoriteItem> = items
        .filter { !it.url.isNullOrBlank() }
        .sortedWith(compareBy<FavoriteItem>({ it.homePageBookmark }, { it.parent ?: 0L }, { it.order }, { if (it.homePageBookmark) it.id else -it.id }))
        .distinctBy { it.url }

    suspend fun get(id: Long): FavoriteItem? = dao.getById(id)

    /** The folder the item is in */
    fun folderOf(item: FavoriteItem): Long = if (item.homePageBookmark) HOME_PAGE else item.parent ?: ROOT

    /** The folders and bookmarks of the [folder], in their order */
    suspend fun children(folder: Long): List<FavoriteItem> =
        if (folder == HOME_PAGE) dao.getHomePageBookmarks() else dao.getChildren(folder)

    /** All folders but [HOME_PAGE] */
    suspend fun folders(): List<FavoriteItem> = dao.getFolders()

    /** The number of bookmarks right in the [folder] */
    suspend fun countBookmarks(folder: Long): Int =
        if (folder == HOME_PAGE) dao.getHomePageBookmarks().size else dao.countBookmarks(folder)

    /** A bookmark of the url, a user's one before a home page one */
    suspend fun findByUrl(url: String): FavoriteItem? = dao.findByUrl(url)

    /**
     * The bookmarks of every folder, Home page included, whose title or url contains the [text] (ignoring
     * case, also outside ASCII, which SQLite's LIKE does not fold): those whose title starts with it first,
     * then the other title matches, then the url ones, each by title.
     */
    suspend fun search(text: String): List<FavoriteItem> {
        val query = text.trim()
        if (query.isEmpty()) return emptyList()
        fun rank(item: FavoriteItem): Int {
            val title = item.title ?: ""
            return when {
                title.startsWith(query, ignoreCase = true) -> 0
                title.contains(query, ignoreCase = true) -> 1
                else -> 2
            }
        }
        return dao.getBookmarks()
            .filter { it.title?.contains(query, ignoreCase = true) == true || it.url?.contains(query, ignoreCase = true) == true }
            .sortedWith(compareBy<FavoriteItem> { rank(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title ?: it.url ?: "" })
    }

    /**
     * Every folder and bookmark as the tree of a bookmarks file: the Home page folder (named [homePageTitle],
     * left out when it is empty) first, as it is shown, then the top level; each folder in its order.
     */
    suspend fun exportTree(homePageTitle: String): List<BookmarksHtml.Node> {
        suspend fun nodes(folder: Long): List<BookmarksHtml.Node> = children(folder).map { item ->
            if (item.isFolder) {
                BookmarksHtml.Folder(item.title ?: "", nodes(item.id))
            } else {
                BookmarksHtml.Link(item.title ?: "", item.url ?: "")
            }
        }
        val homePage = nodes(HOME_PAGE)
        val top = nodes(ROOT)
        return if (homePage.isEmpty()) top else listOf(BookmarksHtml.Folder(homePageTitle, homePage, isHomePage = true)) + top
    }

    /**
     * Adds the folders and bookmarks of a file ([nodes] is its top level) as the [mode] says; [folderName]
     * names the folder of [ImportMode.NEW_FOLDER]. New items of a folder go to its top (to the end of
     * [HOME_PAGE]) in the order of the file.
     */
    suspend fun importBookmarks(nodes: List<BookmarksHtml.Node>, mode: ImportMode, folderName: String): ImportResult {
        //a user's bookmark before a home page one, the newest first, as findByUrl()
        val saved = HashMap<String, FavoriteItem>()
        for (item in dao.getBookmarks().sortedWith(compareBy<FavoriteItem> { it.homePageBookmark }.thenByDescending { it.id })) {
            saved.putIfAbsent(item.url ?: continue, item)
        }
        val importer = Importer(saved)
        val folder = when (mode) {
            ImportMode.NEW_FOLDER -> importer.importIntoNewFolder(nodes, folderName)
            ImportMode.MERGE -> {
                importer.merge(nodes, ROOT)
                null
            }
        }
        return ImportResult(importer.added, importer.updated, importer.skipped, folder)
    }

    private inner class Importer(private val saved: MutableMap<String, FavoriteItem>) {
        var added = 0
        var updated = 0
        var skipped = 0
        /** The addresses of the file met so far */
        private val seen = HashSet<String>()

        suspend fun importIntoNewFolder(nodes: List<BookmarksHtml.Node>, name: String): FavoriteItem? {
            //the bookmarks to add, chosen before anything is made, so that folders that would stay empty are left out
            val toAdd = Collections.newSetFromMap(IdentityHashMap<BookmarksHtml.Link, Boolean>())
            fun choose(nodes: List<BookmarksHtml.Node>) {
                for (node in nodes) when (node) {
                    is BookmarksHtml.Folder -> choose(node.children)
                    is BookmarksHtml.Link -> if (seen.add(node.url) && !saved.containsKey(node.url)) {
                        toAdd += node
                    } else {
                        skipped++
                    }
                }
            }
            choose(nodes)
            if (toAdd.isEmpty()) return null

            fun hasNew(node: BookmarksHtml.Node): Boolean =
                if (node is BookmarksHtml.Folder) node.children.any { hasNew(it) } else node in toAdd

            suspend fun addAll(nodes: List<BookmarksHtml.Node>, parent: Long) {
                val items = ArrayList<FavoriteItem>()
                for (node in nodes) {
                    if (!hasNew(node)) continue
                    val item = newItem(node)
                    add(item, parent)
                    items += item
                    when (node) {
                        is BookmarksHtml.Folder -> addAll(node.children, item.id)
                        is BookmarksHtml.Link -> added++
                    }
                }
                keepFileOrder(items)
            }
            val folder = newItem(BookmarksHtml.Folder(name, emptyList()))
            add(folder, ROOT)
            addAll(nodes, folder.id)
            return folder
        }

        suspend fun merge(nodes: List<BookmarksHtml.Node>, parent: Long) {
            val newItems = ArrayList<FavoriteItem>()
            //the folders of the Home page folder go to the top level
            val subfolders = ArrayList<BookmarksHtml.Folder>()
            for (node in nodes) {
                when (node) {
                    is BookmarksHtml.Folder -> when {
                        node.isHomePage && parent == ROOT -> merge(node.children, HOME_PAGE)
                        parent == HOME_PAGE -> subfolders += node
                        else -> {
                            val title = node.title.trim()
                            var folder = children(parent).firstOrNull {
                                it.isFolder && (it.title ?: "").trim().equals(title, ignoreCase = true)
                            }
                            if (folder == null) {
                                folder = newItem(node)
                                add(folder, parent)
                                newItems += folder
                            }
                            merge(node.children, folder.id)
                        }
                    }
                    is BookmarksHtml.Link -> {
                        val existing = saved[node.url]
                        when {
                            !seen.add(node.url) -> skipped++
                            existing == null -> {
                                val item = newItem(node)
                                add(item, parent)
                                saved[node.url] = item
                                newItems += item
                                added++
                            }
                            existing.title == node.title && folderOf(existing) == parent -> skipped++
                            else -> {
                                existing.title = node.title
                                moveTo(existing, parent)
                                updated++
                            }
                        }
                    }
                }
            }
            keepFileOrder(newItems)
            if (subfolders.isNotEmpty()) merge(subfolders, ROOT)
        }

        private fun newItem(node: BookmarksHtml.Node) = FavoriteItem().apply {
            title = node.title
            url = (node as? BookmarksHtml.Link)?.url
        }

        /**
         * The items were added one by one to the top of their folder, which put them in the reverse order of
         * the file: they get their orders back in the order of the file (new home page bookmarks go to the
         * end, so they keep theirs)
         */
        private suspend fun keepFileOrder(items: List<FavoriteItem>) {
            if (items.size < 2) return
            val orders = items.map { it.order }.sorted()
            items.forEachIndexed { i, item -> item.order = orders[i] }
            dao.update(items)
        }
    }

    /**
     * Inserts (id 0, the id is set to the item) or updates the bookmark.
     */
    suspend fun save(item: FavoriteItem) {
        if (item.id == 0L) {
            item.id = dao.insert(item)
        } else {
            dao.update(item)
        }
    }

    /** Saves a new bookmark or folder in the [folder]; a folder can not go to [HOME_PAGE] */
    suspend fun add(item: FavoriteItem, folder: Long) {
        require(!(item.isFolder && folder == HOME_PAGE))
        place(item, folder)
        save(item)
    }

    /**
     * Moves the item to the [folder] and saves it (with its other changes).
     * @return false if it can not go there: a folder to [HOME_PAGE], to itself or into a folder of its own
     */
    suspend fun moveTo(item: FavoriteItem, folder: Long): Boolean {
        if (item.isFolder && (folder == HOME_PAGE || folder == item.id || isInside(folder, item.id))) return false
        if (folderOf(item) != folder) {
            place(item, folder)
        }
        save(item)
        return true
    }

    /** Whether the [folder] is inside the folder [ancestor] (at any depth) */
    private suspend fun isInside(folder: Long, ancestor: Long): Boolean {
        var id = folder
        val visited = HashSet<Long>()
        while (id != ROOT && id != HOME_PAGE && visited.add(id)) {
            val parent = dao.getById(id)?.parent ?: return false
            if (parent == ancestor) return true
            id = parent
        }
        return false
    }

    private suspend fun place(item: FavoriteItem, folder: Long) {
        if (folder == HOME_PAGE) {
            item.homePageBookmark = true
            item.parent = ROOT
            item.order = nextHomePageOrder()
        } else {
            item.homePageBookmark = false
            item.parent = folder
            item.order = (dao.minOrder(folder) ?: 1) - 1
        }
    }

    /**
     * Moves the item by [offset] places among the items of its kind (folders or bookmarks) of its folder.
     * @return false if there is no place there
     */
    suspend fun move(item: FavoriteItem, offset: Int): Boolean {
        val siblings = children(folderOf(item)).filter { it.isFolder == item.isFolder }.toMutableList()
        val index = siblings.indexOfFirst { it.id == item.id }
        val target = index + offset
        if (index == -1 || target !in siblings.indices) return false
        siblings.add(target, siblings.removeAt(index))
        //orders follow the shown sequence, so equal orders of old bookmarks do not get in the way
        val changed = siblings.filterIndexed { i, sibling ->
            val oldOrder = sibling.order
            sibling.order = i
            oldOrder != i
        }
        dao.update(changed)
        item.order = target
        return true
    }

    /** Deletes the item; a folder goes with everything in it */
    suspend fun delete(item: FavoriteItem) {
        if (item.isFolder) {
            for (child in dao.getChildren(item.id)) {
                delete(child)
            }
        }
        dao.delete(item)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun markAsUseful(id: Long) = dao.markAsUseful(id)

    /** The order of a new home page bookmark: after the others */
    suspend fun nextHomePageOrder(): Int = (dao.maxHomePageOrder() ?: -1) + 1
}
