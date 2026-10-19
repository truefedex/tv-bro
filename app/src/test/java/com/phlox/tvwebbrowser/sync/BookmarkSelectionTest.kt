package com.phlox.tvwebbrowser.sync

import com.phlox.tvwebbrowser.sync.BookmarkSelection.Node
import com.phlox.tvwebbrowser.sync.BookmarkSelection.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkSelectionTest {
    private fun link(name: String, order: Int? = null) = Node(name, "https://$name.com/", order = order)

    private val a = link("a")
    private val b = link("b")
    private val deep = link("deep")
    private val inner = Node("Inner", null, listOf(deep))
    private val news = Node("News", null, listOf(inner, a, b))
    private val h = link("h", order = 4)
    private val home = Node("Home page", null, listOf(h), homePage = true)
    private val empty = Node("Empty", null)
    private val top = link("top")
    private val selection = BookmarkSelection(listOf(home, news, empty, top))

    @Test
    fun nothingChosenSendsNothing() {
        assertEquals(State.NONE, selection.state)
        assertNull(selection.tree())
    }

    @Test
    fun aFolderIsChosenWithAllItsContents() {
        selection.toggle(news)
        assertEquals(State.ALL, selection.state(news))
        assertEquals(State.ALL, selection.state(inner))
        assertEquals(State.PARTIAL, selection.state)
        assertEquals(SyncBookmark.Folder("", all = false, children = listOf(
            SyncBookmark.Folder("News", all = true, children = listOf(
                SyncBookmark.Folder("Inner", all = true, children = listOf(SyncBookmark.Link("deep", "https://deep.com/"))),
                SyncBookmark.Link("a", "https://a.com/"),
                SyncBookmark.Link("b", "https://b.com/")
            ))
        )), selection.tree())

        //toggling a whole folder unchooses it
        selection.toggle(news)
        assertEquals(State.NONE, selection.state(news))
    }

    @Test
    fun aFolderWithSomeOfItsItemsIsSentInPart() {
        selection.toggle(a)
        assertEquals(State.PARTIAL, selection.state(news))
        assertEquals(SyncBookmark.Folder("", all = false, children = listOf(
            SyncBookmark.Folder("News", all = false, children = listOf(SyncBookmark.Link("a", "https://a.com/")))
        )), selection.tree())
        //a folder in part becomes chosen in full
        selection.toggle(news)
        assertEquals(State.ALL, selection.state(news))
    }

    @Test
    fun everythingChosenReplacesEverythingWithTheHomePagePlaces() {
        selection.setAll(true)
        assertEquals(State.ALL, selection.state)
        val tree = selection.tree()!!
        assertEquals(true, tree.all)
        assertEquals(SyncBookmark.Folder("Home page", all = true, homePage = true,
            children = listOf(SyncBookmark.Link("h", "https://h.com/", 4))), tree.children[0])
        assertEquals(SyncBookmark.Folder("Empty", all = true, children = emptyList()), tree.children[2])
    }

    @Test
    fun rowsShowTheContentsOfExpandedFolders() {
        news.expanded = true
        selection.toggle(deep)
        assertEquals(listOf("Home page" to 0, "News" to 0, "Inner" to 1, "a" to 1, "b" to 1, "Empty" to 0, "top" to 0),
            selection.rows().map { it.node.title to it.depth })
        assertEquals(State.PARTIAL, selection.rows()[1].state)
        assertEquals(State.ALL, selection.rows()[2].state)
    }
}
