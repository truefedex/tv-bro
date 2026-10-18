package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.dao.FavoritesDao

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

    suspend fun all(): List<FavoriteItem> = dao.getAll()

    suspend fun homePageBookmarks(): List<FavoriteItem> = dao.getHomePageBookmarks()

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
