package com.phlox.tvwebbrowser.sync

import com.phlox.tvwebbrowser.data.FavoritesRepository

/**
 * The bookmarks of the phone that the user chooses to send, as a tree with checks: a bookmark is chosen
 * or not, a folder is chosen with all that is in it ([State.ALL]), in part or not at all (an empty folder
 * is chosen or not by itself). [tree] is what goes to the TV.
 */
class BookmarkSelection(val nodes: List<Node>) {
    enum class State { NONE, PARTIAL, ALL }

    class Node(
        val title: String,
        /** Null for a folder */
        val url: String?,
        val children: List<Node> = emptyList(),
        val homePage: Boolean = false,
        /** The place of a bookmark on the home page */
        val order: Int? = null
    ) {
        val isFolder: Boolean get() = url == null
        /** A bookmark, or an empty folder, is chosen */
        var checked = false
        var expanded = false
    }

    /** A row of the shown tree: the folders that are not expanded hide their contents */
    data class Row(val node: Node, val depth: Int, val state: State)

    fun state(node: Node): State {
        if (!node.isFolder || node.children.isEmpty()) return if (node.checked) State.ALL else State.NONE
        val states = node.children.map(::state)
        return when {
            states.all { it == State.ALL } -> State.ALL
            states.all { it == State.NONE } -> State.NONE
            else -> State.PARTIAL
        }
    }

    /** The state of everything */
    val state: State get() {
        val states = nodes.map(::state)
        return when {
            states.isNotEmpty() && states.all { it == State.ALL } -> State.ALL
            states.all { it == State.NONE } -> State.NONE
            else -> State.PARTIAL
        }
    }

    /** A node chosen in full is unchosen, any other one is chosen in full */
    fun toggle(node: Node) = setChecked(node, state(node) != State.ALL)

    fun setAll(checked: Boolean) = nodes.forEach { setChecked(it, checked) }

    private fun setChecked(node: Node, checked: Boolean) {
        node.checked = checked
        node.children.forEach { setChecked(it, checked) }
    }

    fun rows(): List<Row> {
        val rows = ArrayList<Row>()
        fun add(nodes: List<Node>, depth: Int) {
            for (node in nodes) {
                rows += Row(node, depth, state(node))
                if (node.isFolder && node.expanded) add(node.children, depth + 1)
            }
        }
        add(nodes, 0)
        return rows
    }

    /** What is chosen, null if nothing is */
    fun tree(): SyncBookmark.Folder? {
        fun convert(node: Node): SyncBookmark? {
            val state = state(node)
            if (state == State.NONE) return null
            return if (node.isFolder) {
                SyncBookmark.Folder(node.title, all = state == State.ALL, children = node.children.mapNotNull(::convert),
                    homePage = node.homePage)
            } else {
                SyncBookmark.Link(node.title, node.url!!, node.order)
            }
        }
        val state = state
        if (state == State.NONE) return null
        return SyncBookmark.Folder("", all = state == State.ALL, children = nodes.mapNotNull(::convert))
    }

    companion object {
        /** The Home page folder (named [homePageTitle], even if it is empty) first, then the top level */
        suspend fun load(favorites: FavoritesRepository, homePageTitle: String): BookmarkSelection {
            suspend fun nodes(folder: Long): List<Node> = favorites.children(folder).mapNotNull { item ->
                when {
                    item.isFolder -> Node(item.title ?: "", null, nodes(item.id))
                    SyncRequests.isAllowedUrl(item.url!!) -> Node(item.title ?: "", item.url,
                        order = if (item.homePageBookmark) item.order else null)
                    else -> null
                }
            }
            val home = Node(homePageTitle, null, nodes(FavoritesRepository.HOME_PAGE), homePage = true)
            return BookmarkSelection(listOf(home) + nodes(FavoritesRepository.ROOT))
        }
    }
}
