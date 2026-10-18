package com.phlox.tvwebbrowser.activity.main

import androidx.room.Room
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Action
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Focus
import com.phlox.tvwebbrowser.activity.main.BookmarksNavigator.Screen
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.HOME_PAGE
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BookmarksNavigatorTest {
    private val context = RuntimeEnvironment.getApplication()
    //queries run where they are called: every change of the screen is rendered before the call returns
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
    private val favorites = DataRepositories(db).favorites

    private var page: BookmarksNavigator.PageInfo? = BookmarksNavigator.PageInfo("Example", "https://example.com/")
    private lateinit var screen: Screen
    private lateinit var focus: Focus
    private val messages = ArrayList<String>()
    private var closed = false
    private var opened: String? = null

    private val navigator = BookmarksNavigator(context, favorites, CoroutineScope(Dispatchers.Unconfined),
        object : BookmarksNavigator.Host {
            override fun currentPage() = page
            override fun render(screen: Screen, focus: Focus) {
                this@BookmarksNavigatorTest.screen = screen
                this@BookmarksNavigatorTest.focus = focus
            }
            override fun openBookmark(url: String) { opened = url }
            override fun openBookmarkInNewTab(url: String) { opened = url }
            override fun onBookmarksChanged() {}
            override fun showMessage(icon: Int, message: String) { messages += message }
            override fun confirm(message: String, action: () -> Unit) = action()
            override fun close() { closed = true }
        })

    @After
    fun tearDown() {
        db.close()
    }

    private fun bookmark(title: String, folder: Long = ROOT) = FavoriteItem().apply {
        this.title = title
        url = "https://$title.com/"
    }.also { runBlocking { favorites.add(it, folder) } }

    private fun folder(title: String, parent: Long = ROOT) = FavoriteItem().apply { this.title = title }
        .also { runBlocking { favorites.add(it, parent) } }

    private val folderScreen get() = screen as Screen.Folder
    private fun rowKeys() = when (val s = screen) {
        is Screen.Folder -> s.rows.map { it.key }
        is Screen.PickFolder -> s.rows.map { it.key }
        else -> emptyList()
    }
    private fun row(key: String) = folderScreen.rows.first { it.key == key }

    @Test
    fun topLevelHasTheHomePageFolderFirstAndTheRowOfThisPage() {
        bookmark("tile", HOME_PAGE)
        val f = folder("f")
        val a = bookmark("a")
        navigator.start()
        assertEquals(listOf("f$HOME_PAGE", "f${f.id}", "b${a.id}"), rowKeys())
        assertFalse(folderScreen.canGoBack)
        assertFalse(folderScreen.thisPage!!.bookmarked)

        page = null
        navigator.start()
        assertNull(folderScreen.thisPage)
    }

    @Test
    fun thisPageIsAddedToTheFolderShownAndFocused() {
        val f = folder("f")
        navigator.start()
        navigator.onRowClick(row("f${f.id}"))
        navigator.onThisPageClick()
        val added = favorites.let { runBlocking { it.children(f.id) } }.single()
        assertEquals("https://example.com/", added.url)
        assertEquals("b${added.id}", focus.key)
        assertTrue(folderScreen.thisPage!!.bookmarked)
        assertEquals(1, messages.size)

        //the row of a bookmarked page opens its form
        navigator.onThisPageClick()
        assertTrue(screen is Screen.Form)
    }

    @Test
    fun backFocusesTheFolderThatWasOpen() {
        val f = folder("f")
        navigator.start()
        navigator.onRowClick(row("f${f.id}"))
        assertEquals("f", screen.title)
        assertTrue(screen.canGoBack)
        assertTrue(navigator.back())
        assertEquals("f${f.id}", focus.key)
        assertFalse(navigator.back())
    }

    @Test
    fun bookmarkOpensAndIsFocusedNextTime() {
        val a = bookmark("a")
        bookmark("b")
        navigator.start()
        navigator.onRowClick(row("b${a.id}"))
        assertEquals("https://a.com/", opened)
        navigator.start()
        assertEquals("b${a.id}", focus.key)
    }

    @Test
    fun bookmarkMovesToTheChosenFolder() {
        val f = folder("f")
        val a = bookmark("a")
        navigator.start()
        navigator.perform(row("b${a.id}"), Action.MOVE_TO_FOLDER)
        assertEquals(listOf("t$ROOT", "t$HOME_PAGE", "t${f.id}", "new"), rowKeys())
        assertEquals("t$ROOT", focus.key)

        navigator.onRowClick((screen as Screen.PickFolder).rows.first { it.key == "t${f.id}" })
        assertEquals(listOf("f${f.id}"), rowKeys())
        assertEquals(f.id, runBlocking { favorites.get(a.id)!!.parent })
        assertEquals(1, messages.size)
    }

    @Test
    fun folderDoesNotGoIntoItselfOrTheHomePage() {
        val outer = folder("outer")
        val inner = folder("inner", outer.id)
        val other = folder("other")
        navigator.start()
        navigator.perform(row("f${outer.id}"), Action.MOVE_TO_FOLDER)
        assertEquals(listOf("t$ROOT", "t${other.id}", "new"), rowKeys())
        assertFalse(rowKeys().contains("t${inner.id}"))
    }

    @Test
    fun formSavesTheNameAndTheChosenFolder() {
        val f = folder("f")
        val a = bookmark("a")
        navigator.start()
        navigator.perform(row("b${a.id}"), Action.EDIT)
        val form = screen as Screen.Form
        assertEquals("a", form.name)
        assertEquals(BookmarksNavigator.FORM_NAME, focus.key)

        navigator.chooseFormFolder()
        navigator.onRowClick((screen as Screen.PickFolder).rows.first { it.key == "t${f.id}" })
        assertEquals("f", (screen as Screen.Form).folderTitle)
        assertEquals(BookmarksNavigator.FORM_FOLDER, focus.key)

        assertEquals(BookmarksNavigator.Field.URL, navigator.saveForm("renamed", " "))
        assertNull(navigator.saveForm("renamed", "https://a.com/"))
        //back to the top level, the bookmark has left it
        assertEquals(listOf("f${f.id}"), rowKeys())
        val saved = runBlocking { favorites.get(a.id)!! }
        assertEquals("renamed", saved.title)
        assertEquals(f.id, saved.parent)
    }

    @Test
    fun newFolderIsCreatedInTheFolderShown() {
        val f = folder("f")
        navigator.start()
        navigator.onRowClick(row("f${f.id}"))
        navigator.createFolder()
        assertEquals(BookmarksNavigator.Field.NAME, navigator.saveForm("  ", ""))
        assertNull(navigator.saveForm("sub", ""))
        val sub = runBlocking { favorites.children(f.id) }.single()
        assertTrue(sub.isFolder)
        assertEquals("f${sub.id}", focus.key)
    }

    @Test
    fun formOpenedFirstClosesWhenSaved() {
        val a = bookmark("a")
        navigator.startWithForm(a)
        assertTrue(screen is Screen.Form)
        assertNull(navigator.saveForm("b", "https://a.com/"))
        assertTrue(closed)
    }

    @Test
    fun rowActionsDependOnThePlaceAndTheLook() {
        bookmark("tile", HOME_PAGE)
        val a = bookmark("a")
        val b = bookmark("b")
        navigator.start()
        assertTrue(navigator.actionsOf(row("f$HOME_PAGE")).isEmpty())
        //b is the newest, at the top
        val first = navigator.actionsOf(row("b${b.id}")).associate { it.action to it.enabled }
        assertEquals(false, first[Action.MOVE_UP])
        assertEquals(true, first[Action.MOVE_DOWN])
        assertFalse(navigator.actionsOf(row("b${a.id}"), reorder = false).any { it.action == Action.MOVE_UP })

        navigator.perform(row("b${a.id}"), Action.MOVE_UP)
        assertEquals(listOf("f$HOME_PAGE", "b${a.id}", "b${b.id}"), rowKeys())
        assertEquals("b${a.id}", focus.key)
    }

    @Test
    fun deletedRowGivesItsPlaceToTheNextOne() {
        val a = bookmark("a")
        val b = bookmark("b")
        navigator.start()
        navigator.perform(row("b${b.id}"), Action.DELETE)
        assertEquals(listOf("b${a.id}"), rowKeys())
        assertEquals(0, focus.index)
    }

    private val searchScreen get() = screen as Screen.Search

    @Test
    fun searchFindsBookmarksOfEveryFolderNamingTheirFolders() {
        val f = folder("Новини")
        val tile = bookmark("tile", HOME_PAGE)
        val inFolder = FavoriteItem().apply {
            title = "Українська правда"
            url = "https://www.pravda.com.ua/"
        }.also { runBlocking { favorites.add(it, f.id) } }
        val top = bookmark("toptile")
        bookmark("other")
        navigator.start()
        navigator.startSearch()
        assertEquals(BookmarksNavigator.SEARCH_FIELD, focus.key)
        assertTrue(searchScreen.rows.isEmpty())
        assertNull(searchScreen.status)

        navigator.search("TILE")
        //the title that starts with it first
        assertEquals(listOf("b${tile.id}", "b${top.id}"), searchScreen.rows.map { it.key })
        assertEquals(listOf("Home page", "Bookmarks"),
            searchScreen.rows.map { (it as BookmarksNavigator.Row.Bookmark).folderTitle })
        assertEquals("2 bookmarks in all folders", searchScreen.status)
        assertEquals(BookmarksNavigator.SEARCH_FIELD, focus.key)

        //case is ignored outside ASCII too, and urls match
        navigator.search("УКРАЇН")
        assertEquals(listOf("b${inFolder.id}"), searchScreen.rows.map { it.key })
        assertEquals("Новини", (searchScreen.rows.single() as BookmarksNavigator.Row.Bookmark).folderTitle)
        navigator.search("pravda.com")
        assertEquals(listOf("b${inFolder.id}"), searchScreen.rows.map { it.key })

        navigator.search("nothing")
        assertTrue(searchScreen.rows.isEmpty())
        assertEquals("No bookmarks found", searchScreen.status)
    }

    @Test
    fun backEndsTheSearchThenWorksAsBefore() {
        bookmark("a")
        navigator.start()
        navigator.startSearch("a")
        assertEquals("a", searchScreen.query)
        assertEquals(1, searchScreen.rows.size)
        assertTrue(navigator.back())
        assertTrue(screen is Screen.Folder)
        assertEquals(BookmarksNavigator.SEARCH_BUTTON, focus.key)
        assertFalse(navigator.back())
    }

    @Test
    fun resultsOpenAndActLikeBookmarksWithoutMoves() {
        val a = bookmark("alpha")
        val b = bookmark("alpine")
        navigator.start()
        navigator.startSearch("alp")
        val result = searchScreen.rows.first { it.key == "b${a.id}" }
        val actions = navigator.actionsOf(result).map { it.action }
        assertEquals(listOf(Action.OPEN_IN_NEW_TAB, Action.EDIT, Action.MOVE_TO_FOLDER, Action.DELETE), actions)

        //the form goes back to the results, which follow the change
        navigator.perform(result, Action.EDIT)
        assertNull(navigator.saveForm("beta", "https://alpha.com/"))
        //found by its url now, after the title match
        assertEquals(listOf("b${b.id}", "b${a.id}"), searchScreen.rows.map { it.key })
        assertEquals("b${a.id}", focus.key)
        assertEquals("alp", searchScreen.query)

        navigator.perform(searchScreen.rows.first { it.key == "b${b.id}" }, Action.DELETE)
        assertEquals(listOf("b${a.id}"), searchScreen.rows.map { it.key })

        navigator.onRowClick(searchScreen.rows.single())
        assertEquals("https://alpha.com/", opened)
    }
}
