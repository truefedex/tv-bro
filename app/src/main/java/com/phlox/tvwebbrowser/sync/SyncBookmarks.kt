package com.phlox.tvwebbrowser.sync

import androidx.room.withTransaction
import com.phlox.tvwebbrowser.data.BookmarksHtml
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.model.FavoriteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bookmarks one device sends to another: the folders and bookmarks the user chose (a phone sends them to a
 * TV, or takes them from all the bookmarks of the TV, [fromFavorites]). A folder chosen with all that is in
 * it ([Folder.all]) replaces the folder of the device that gets it; a folder chosen in part only adds and
 * updates.
 */
sealed class SyncBookmark {
    abstract val title: String

    /**
     * @param all the whole folder was chosen: the TV folder becomes the same (what else is in it goes)
     * @param homePage the Home page folder (only at the top level)
     */
    data class Folder(
        override val title: String,
        val all: Boolean,
        val children: List<SyncBookmark>,
        val homePage: Boolean = false
    ) : SyncBookmark()

    /** @param order the place on the home page of a bookmark of the Home page folder */
    data class Link(override val title: String, val url: String, val order: Int? = null) : SyncBookmark()

    companion object {
        private const val MAX_DEPTH = 64
        private const val MAX_NODES = 100_000

        fun toJson(root: Folder): JSONObject = node(root)

        private fun node(node: SyncBookmark): JSONObject = when (node) {
            is Folder -> JSONObject().put("f", node.title).put("all", node.all)
                .apply { if (node.homePage) put("home", true) }
                .put("c", JSONArray(node.children.map(::node)))
            is Link -> JSONObject().put("t", node.title).put("u", node.url)
                .apply { if (node.order != null) put("o", node.order) }
        }

        /**
         * The tree of a request; bookmarks of pages other than http and https are left out.
         * @throws SyncException [SyncChannel.BAD_MESSAGE] if it is not a tree or it is too big
         */
        fun fromJson(json: JSONObject): Folder {
            var count = 0
            fun parse(json: JSONObject, depth: Int): SyncBookmark? {
                if (depth > MAX_DEPTH || ++count > MAX_NODES) throw SyncException(SyncChannel.BAD_MESSAGE)
                if (json.has("u")) {
                    val url = json.optString("u")
                    if (!SyncRequests.isAllowedUrl(url)) return null
                    val order = if (json.has("o")) json.optInt("o") else null
                    return Link(json.optString("t").take(SyncRequests.MAX_TITLE_LENGTH), url, order)
                }
                val children = json.optJSONArray("c") ?: JSONArray()
                return Folder(
                    title = json.optString("f").take(SyncRequests.MAX_TITLE_LENGTH),
                    all = json.optBoolean("all"),
                    children = (0 until children.length()).mapNotNull { i ->
                        children.optJSONObject(i)?.let { parse(it, depth + 1) }
                    },
                    //only the top level holds the Home page folder
                    homePage = depth == 1 && json.optBoolean("home")
                )
            }
            return parse(json, 0) as? Folder ?: throw SyncException(SyncChannel.BAD_MESSAGE)
        }

        /**
         * All the bookmarks of the device: the Home page folder (named [homePageTitle], even if it is empty,
         * its bookmarks with their places on the page) first, then the top level; bookmarks of pages other
         * than http and https are left out
         */
        suspend fun fromFavorites(favorites: FavoritesRepository, homePageTitle: String): Folder {
            suspend fun nodes(folder: Long): List<SyncBookmark> = favorites.children(folder).mapNotNull { item ->
                when {
                    item.isFolder -> Folder(item.title ?: "", all = true, children = nodes(item.id))
                    SyncRequests.isAllowedUrl(item.url!!) -> Link(item.title ?: "", item.url!!,
                        order = if (item.homePageBookmark) item.order else null)
                    else -> null
                }
            }
            val home = Folder(homePageTitle, all = true, children = nodes(FavoritesRepository.HOME_PAGE), homePage = true)
            return Folder("", all = true, children = listOf(home) + nodes(FavoritesRepository.ROOT))
        }

        /**
         * The tree that brings back the bookmarks of a file of [FavoritesRepository.exportTree]: all of it,
         * the Home page folder too (it is left out of the file when it is empty).
         */
        fun everything(nodes: List<BookmarksHtml.Node>): Folder {
            fun convert(node: BookmarksHtml.Node): SyncBookmark = when (node) {
                is BookmarksHtml.Folder -> Folder(node.title, all = true, children = node.children.map(::convert),
                    homePage = node.isHomePage)
                is BookmarksHtml.Link -> Link(node.title, node.url)
            }
            val children = nodes.map(::convert)
            val home = if (children.any { it is Folder && it.homePage }) emptyList()
                else listOf(Folder("", all = true, children = emptyList(), homePage = true))
            return Folder("", all = true, children = home + children)
        }
    }
}

/**
 * Puts the bookmarks a phone sent into those of this device ([replace]), or tells what that would change
 * (`dryRun`): both go the same way, so the preview shows what is done.
 *
 * Folders are matched by their title (ignoring case and spaces at the ends), bookmarks by their address,
 * each in the folder of the same place. In a folder sent with [SyncBookmark.Folder.all], the items the phone
 * does not have are deleted and the items get the order of the phone; in a folder sent in part, the new items
 * go to its top (to the end of the Home page folder) in the order of the phone. A bookmark of the same
 * address in another folder of the TV stays there: it is not moved.
 */
class BookmarksReplacer(private val favorites: FavoritesRepository) {
    class Changes(val added: Int, val updated: Int, val removed: Int, val removedTitles: List<String>) {
        val isEmpty: Boolean get() = added == 0 && updated == 0 && removed == 0
    }

    suspend fun replace(root: SyncBookmark.Folder, dryRun: Boolean): Changes {
        val run = Run(dryRun)
        run.syncFolder(FavoritesRepository.ROOT, root.children, root.all)
        root.children.firstOrNull { it is SyncBookmark.Folder && it.homePage }?.let {
            run.syncHomePage(it as SyncBookmark.Folder)
        }
        return Changes(run.added, run.updated, run.removed, run.removedTitles)
    }

    private inner class Run(private val dryRun: Boolean) {
        var added = 0
        var updated = 0
        var removed = 0
        val removedTitles = ArrayList<String>()

        /** @param folder null for a folder that is only to be made (in a dry run) */
        suspend fun syncFolder(folder: Long?, nodes: List<SyncBookmark>, all: Boolean) {
            val existing = if (folder == null) emptyList() else favorites.children(folder)
            val matched = HashSet<Long>()
            //the items in the order of the nodes: an item of the folder, or null for a new one
            val placed = ArrayList<FavoriteItem?>()
            val newNodes = ArrayList<Pair<Int, SyncBookmark>>()
            val matchedFolders = ArrayList<Pair<Long, SyncBookmark.Folder>>()
            for (node in nodes) {
                val match = when (node) {
                    is SyncBookmark.Folder -> {
                        if (node.homePage) continue
                        val title = node.title.trim()
                        existing.firstOrNull {
                            it.isFolder && it.id !in matched && (it.title ?: "").trim().equals(title, ignoreCase = true)
                        }?.also { matchedFolders += it.id to node }
                    }
                    is SyncBookmark.Link -> existing.firstOrNull { !it.isFolder && it.id !in matched && it.url == node.url }
                        ?.also { updateTitle(it, node.title) }
                }
                if (match != null) {
                    matched += match.id
                } else {
                    newNodes += placed.size to node
                    if (node is SyncBookmark.Link) added++
                }
                placed += match
            }
            if (all) {
                for (item in existing) {
                    if (item.id !in matched) remove(item)
                }
            }
            if (!dryRun) {
                //each new item goes to the top: the last one first, so they end up in the order of the nodes
                for ((index, node) in newNodes.asReversed()) {
                    val item = FavoriteItem().apply {
                        title = node.title
                        url = (node as? SyncBookmark.Link)?.url
                    }
                    favorites.add(item, folder!!)
                    placed[index] = item
                }
                if (all) {
                    //folders come before bookmarks whatever their orders
                    placed.forEachIndexed { i, item ->
                        if (item != null && item.order != i) {
                            item.order = i
                            favorites.save(item)
                        }
                    }
                }
            }
            for ((index, node) in newNodes) {
                if (node is SyncBookmark.Folder) syncFolder(placed[index]?.id, node.children, all = true)
            }
            for ((id, node) in matchedFolders) {
                syncFolder(id, node.children, node.all)
            }
        }

        /** The Home page folder holds bookmarks only; a link's order is its place on the page */
        suspend fun syncHomePage(node: SyncBookmark.Folder) {
            val folder = FavoritesRepository.HOME_PAGE
            val existing = favorites.children(folder)
            val matched = HashSet<Long>()
            val placed = ArrayList<Pair<SyncBookmark.Link, FavoriteItem?>>()
            for (link in node.children.filterIsInstance<SyncBookmark.Link>()) {
                val match = existing.firstOrNull { !it.isFolder && it.id !in matched && it.url == link.url }
                if (match != null) {
                    matched += match.id
                    updateTitle(match, link.title)
                } else {
                    added++
                }
                placed += link to match
            }
            if (node.all) {
                for (item in existing) {
                    if (item.id !in matched) remove(item)
                }
            }
            if (dryRun) return
            //the places of the phone, if they are all known and different
            val orders = placed.map { it.first.order }
            val keepOrders = node.all && orders.none { it == null } && orders.toSet().size == orders.size
            placed.forEachIndexed { i, (link, match) ->
                val item = match ?: FavoriteItem().apply {
                    title = link.title
                    url = link.url
                    //new ones go to the end
                    favorites.add(this, folder)
                }
                if (node.all) {
                    val order = if (keepOrders) link.order!! else i
                    if (item.order != order) {
                        item.order = order
                        favorites.save(item)
                    }
                }
            }
        }

        private suspend fun updateTitle(item: FavoriteItem, title: String) {
            if (item.title == title) return
            updated++
            if (!dryRun) {
                item.title = title
                favorites.save(item)
            }
        }

        private suspend fun remove(item: FavoriteItem) {
            removed += countBookmarks(item)
            if (removedTitles.size < MAX_REMOVED_TITLES) removedTitles += item.title?.ifBlank { null } ?: item.url ?: ""
            if (!dryRun) favorites.delete(item)
        }

        private suspend fun countBookmarks(item: FavoriteItem): Int =
            if (!item.isFolder) 1 else favorites.children(item.id).sumOf { countBookmarks(it) }
    }

    companion object {
        const val MAX_REMOVED_TITLES = 20
    }
}

/**
 * The bookmarks of a device as they were before the last change made by the sync, in a bookmarks file:
 * they can be brought back ([SyncBookmark.everything]).
 */
class BookmarksBackup(dir: File) {
    private val file = File(dir, "sync/bookmarks_before_sync.html")

    val exists: Boolean get() = file.exists()

    fun save(nodes: List<BookmarksHtml.Node>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(BookmarksHtml.write(nodes))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    /** @return null if there is no backup */
    fun read(): List<BookmarksHtml.Node>? = if (file.exists()) BookmarksHtml.parse(file.readText()) else null

    fun delete() {
        file.delete()
    }
}

/**
 * The bookmarks of this device as the sync reads and changes them, one change at a time: a TV changes them
 * for a phone, a phone for the bookmarks it takes from a TV. A change keeps the bookmarks of before in the
 * [backup], so it can be undone ([restore]).
 */
class LocalBookmarks(
    private val data: () -> DataRepositories,
    private val backup: BookmarksBackup,
    private val homePageTitle: String
) {
    private val lock = Mutex()

    /** A change can be undone */
    val canRestore: Boolean get() = backup.exists

    /** All the bookmarks, as they are sent ([SyncBookmark.fromFavorites]) */
    suspend fun tree(): SyncBookmark.Folder = SyncBookmark.fromFavorites(data().favorites, homePageTitle)

    /** What [replace] would change, changing nothing */
    suspend fun preview(tree: SyncBookmark.Folder): BookmarksReplacer.Changes = lock.withLock { run(tree, dryRun = true) }

    /**
     * Puts the bookmarks of the [tree] into these ones, keeping these in the backup first (if anything changes).
     * @param expected the changes of the preview the user saw
     * @throws SyncException [SyncRequests.CHANGED] if it would now change anything else (nothing is changed)
     */
    suspend fun replace(tree: SyncBookmark.Folder, expected: BookmarksReplacer.Changes?): BookmarksReplacer.Changes =
        lock.withLock {
            val preview = run(tree, dryRun = true)
            if (expected != null && (expected.added != preview.added || expected.updated != preview.updated ||
                    expected.removed != preview.removed)) {
                throw SyncException(SyncRequests.CHANGED)
            }
            if (preview.isEmpty) return@withLock preview
            val before = data().favorites.exportTree(homePageTitle)
            withContext(Dispatchers.IO) { backup.save(before) }
            run(tree, dryRun = false)
        }

    /** Brings back the bookmarks of before the last change; null if there is no backup */
    suspend fun restore(): BookmarksReplacer.Changes? = lock.withLock {
        val nodes = withContext(Dispatchers.IO) { backup.read() } ?: return@withLock null
        val changes = run(SyncBookmark.everything(nodes), dryRun = false)
        withContext(Dispatchers.IO) { backup.delete() }
        changes
    }

    private suspend fun run(tree: SyncBookmark.Folder, dryRun: Boolean): BookmarksReplacer.Changes {
        val data = data()
        return data.database.withTransaction { BookmarksReplacer(data.favorites).replace(tree, dryRun) }
    }
}
