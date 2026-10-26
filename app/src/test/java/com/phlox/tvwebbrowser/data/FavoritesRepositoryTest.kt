package com.phlox.tvwebbrowser.data

import androidx.room.Room
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.HOME_PAGE
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
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
class FavoritesRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val favorites = DataRepositories(db).favorites

    @After
    fun tearDown() {
        db.close()
    }

    private fun bookmark(title: String) = FavoriteItem().apply {
        this.title = title
        url = "https://$title.com/"
    }

    private fun folder(title: String) = FavoriteItem().apply { this.title = title }

    private suspend fun titles(folder: Long) = favorites.children(folder).map { it.title }

    @Test
    fun foldersComeFirstAndNewItemsGoToTheTop() = runBlocking {
        favorites.add(bookmark("a"), ROOT)
        favorites.add(folder("f1"), ROOT)
        favorites.add(bookmark("b"), ROOT)
        favorites.add(folder("f2"), ROOT)
        assertEquals(listOf("f2", "f1", "b", "a"), titles(ROOT))
    }

    @Test
    fun oldBookmarksWithEqualOrdersAreNewestFirst() = runBlocking {
        //bookmarks saved before folders all have the order 0
        favorites.save(bookmark("old"))
        favorites.save(bookmark("new"))
        favorites.add(bookmark("added"), ROOT)
        assertEquals(listOf("added", "new", "old"), titles(ROOT))
    }

    @Test
    fun homePageBookmarksGoToTheEnd() = runBlocking {
        favorites.add(bookmark("a"), HOME_PAGE)
        favorites.add(bookmark("b"), HOME_PAGE)
        assertEquals(listOf("a", "b"), titles(HOME_PAGE))
        assertEquals(listOf("a", "b"), favorites.homePageBookmarks().map { it.title })
        //they are not in the top level
        assertTrue(favorites.children(ROOT).isEmpty())
        assertEquals(2, favorites.countBookmarks(HOME_PAGE))
    }

    @Test
    fun bookmarksAreCountedInTheirFolder() = runBlocking {
        val f = folder("f")
        favorites.add(f, ROOT)
        favorites.add(bookmark("a"), f.id)
        favorites.add(bookmark("b"), f.id)
        favorites.add(folder("sub"), f.id)
        assertEquals(2, favorites.countBookmarks(f.id))
        assertEquals(listOf("sub", "b", "a"), titles(f.id))
    }

    @Test
    fun movingUpAndDownReordersBookmarksOfTheFolder() = runBlocking {
        val a = bookmark("a")
        favorites.save(a)
        val b = bookmark("b")
        favorites.save(b)
        val c = bookmark("c")
        favorites.save(c)
        favorites.add(folder("f"), ROOT)
        assertEquals(listOf("f", "c", "b", "a"), titles(ROOT))

        assertTrue(favorites.move(a, -1))
        assertEquals(listOf("f", "c", "a", "b"), titles(ROOT))
        assertTrue(favorites.move(a, -1))
        assertEquals(listOf("f", "a", "c", "b"), titles(ROOT))
        //bookmarks do not go above folders
        assertFalse(favorites.move(a, -1))
        assertTrue(favorites.move(c, 1))
        assertEquals(listOf("f", "a", "b", "c"), titles(ROOT))
        assertFalse(favorites.move(c, 1))
    }

    @Test
    fun movingInTheHomePageReordersItsTiles() = runBlocking {
        val a = bookmark("a")
        favorites.add(a, HOME_PAGE)
        favorites.add(bookmark("b"), HOME_PAGE)
        assertTrue(favorites.move(a, 1))
        assertEquals(listOf("b", "a"), favorites.homePageBookmarks().map { it.title })
    }

    @Test
    fun bookmarksMoveBetweenFoldersAndTheHomePage() = runBlocking {
        val f = folder("f")
        favorites.add(f, ROOT)
        val a = bookmark("a")
        favorites.add(a, ROOT)

        assertTrue(favorites.moveTo(a, f.id))
        assertEquals(listOf("a"), titles(f.id))
        assertEquals(f.id, favorites.folderOf(favorites.get(a.id)!!))

        assertTrue(favorites.moveTo(a, HOME_PAGE))
        assertTrue(favorites.children(f.id).isEmpty())
        assertEquals(listOf("a"), titles(HOME_PAGE))

        assertTrue(favorites.moveTo(a, ROOT))
        assertEquals(listOf("f", "a"), titles(ROOT))
        assertTrue(favorites.homePageBookmarks().isEmpty())
    }

    @Test
    fun foldersDoNotGoIntoThemselvesOrTheHomePage() = runBlocking {
        val outer = folder("outer")
        favorites.add(outer, ROOT)
        val inner = folder("inner")
        favorites.add(inner, outer.id)
        assertFalse(favorites.moveTo(outer, outer.id))
        assertFalse(favorites.moveTo(outer, inner.id))
        assertFalse(favorites.moveTo(outer, HOME_PAGE))
        assertTrue(favorites.moveTo(inner, ROOT))
        assertEquals(listOf("inner", "outer"), titles(ROOT))
    }

    @Test
    fun folderIsDeletedWithEverythingInIt() = runBlocking {
        val outer = folder("outer")
        favorites.add(outer, ROOT)
        val inner = folder("inner")
        favorites.add(inner, outer.id)
        val a = bookmark("a")
        favorites.add(a, inner.id)
        val kept = bookmark("kept")
        favorites.add(kept, ROOT)

        favorites.delete(outer)
        assertEquals(listOf("kept"), titles(ROOT))
        assertNull(favorites.get(inner.id))
        assertNull(favorites.get(a.id))
    }

    @Test
    fun bookmarkOfAPageIsFound() = runBlocking {
        favorites.add(bookmark("tile"), HOME_PAGE)
        assertEquals("tile", favorites.findByUrl("https://tile.com/")?.title)
        //the user's own bookmark is found before a home page one
        favorites.add(bookmark("tile").apply { title = "mine" }, ROOT)
        assertEquals("mine", favorites.findByUrl("https://tile.com/")?.title)
        assertNull(favorites.findByUrl("https://other.com/"))
    }

    @Test
    fun searchMatchesTitlesAndUrlsOfEveryFolderIgnoringCase() = runBlocking {
        val f = folder("f")
        favorites.add(f, ROOT)
        favorites.add(bookmark("tile"), HOME_PAGE)
        favorites.add(bookmark("x").apply { title = "My tiles" }, f.id)
        favorites.add(bookmark("tiled").apply { title = "Other" }, ROOT)
        favorites.add(bookmark("y").apply { title = "Ёлка" }, ROOT)
        assertEquals(listOf("tile", "My tiles", "Other"), favorites.search(" TiLe ").map { it.title })
        assertEquals(listOf("Ёлка"), favorites.search("ёЛ").map { it.title })
        assertTrue(favorites.search(" ").isEmpty())
        //folders are not found
        assertTrue(favorites.search("f").none { it.isFolder })
    }

    @Test
    fun exportedTreeHasTheHomePageFirstAndFoldersInTheirOrder() = runBlocking {
        assertTrue(favorites.exportTree("Home page").isEmpty())
        val outer = folder("outer")
        favorites.add(outer, ROOT)
        val inner = folder("inner")
        favorites.add(inner, outer.id)
        favorites.add(bookmark("deep"), inner.id)
        favorites.add(bookmark("a"), ROOT)
        favorites.add(bookmark("b"), ROOT)
        assertEquals(listOf(
            BookmarksHtml.Folder("outer", listOf(BookmarksHtml.Folder("inner", listOf(BookmarksHtml.Link("deep", "https://deep.com/"))))),
            BookmarksHtml.Link("b", "https://b.com/"),
            BookmarksHtml.Link("a", "https://a.com/")
        ), favorites.exportTree("Home page"))

        favorites.add(bookmark("t1"), HOME_PAGE)
        favorites.add(bookmark("t2"), HOME_PAGE)
        assertEquals(BookmarksHtml.Folder("Home page", listOf(BookmarksHtml.Link("t1", "https://t1.com/"),
            BookmarksHtml.Link("t2", "https://t2.com/")), isHomePage = true), favorites.exportTree("Home page").first())
    }

    private fun link(title: String) = BookmarksHtml.Link(title, "https://$title.com/")

    @Test
    fun importIntoANewFolderKeepsTheTreeAndSkipsSavedAddresses() = runBlocking {
        favorites.add(bookmark("saved"), ROOT)
        val result = favorites.importBookmarks(listOf(
            BookmarksHtml.Folder("Home page", listOf(link("saved"), link("t1"), link("t2")), isHomePage = true),
            BookmarksHtml.Folder("Empty", emptyList()),
            BookmarksHtml.Folder("Only saved", listOf(link("saved"))),
            BookmarksHtml.Folder("Outer", listOf(BookmarksHtml.Folder("Inner", listOf(link("deep"))))),
            link("b"),
            link("c"),
            link("b")
        ), FavoritesRepository.ImportMode.NEW_FOLDER, "file")
        assertEquals(5, result.added)
        assertEquals(0, result.updated)
        assertEquals(3, result.skipped)
        val folder = result.folder!!
        assertEquals(listOf("file", "saved"), titles(ROOT))
        //the file's order, folders first; folders that would be empty are not made
        assertEquals(listOf("Home page", "Outer", "b", "c"), titles(folder.id))
        val homePage = favorites.children(folder.id).first()
        assertEquals(listOf("t1", "t2"), titles(homePage.id))
        assertFalse(favorites.children(homePage.id).any { it.homePageBookmark })
        assertTrue(favorites.children(HOME_PAGE).isEmpty())
        val outer = favorites.children(folder.id)[1]
        assertEquals(listOf("deep"), titles(favorites.children(outer.id).single().id))

        //nothing new: no folder
        val again = favorites.importBookmarks(listOf(link("b")), FavoritesRepository.ImportMode.NEW_FOLDER, "again")
        assertNull(again.folder)
        assertEquals(1, again.skipped)
        assertEquals(listOf("file", "saved"), titles(ROOT))
    }

    @Test
    fun mergeJoinsFoldersAndTheHomePageAndOverwritesSavedAddresses() = runBlocking {
        val news = folder("News")
        favorites.add(news, ROOT)
        favorites.add(bookmark("n1"), news.id)
        favorites.add(bookmark("x").apply { title = "Old x" }, ROOT)
        favorites.add(bookmark("t1"), HOME_PAGE)
        val result = favorites.importBookmarks(listOf(
            BookmarksHtml.Folder("Home page", listOf(link("t1"), link("t2"), BookmarksHtml.Folder("Sub", listOf(link("s")))),
                isHomePage = true),
            BookmarksHtml.Folder(" news ", listOf(link("n2"), BookmarksHtml.Link("New x", "https://x.com/"))),
            link("y"),
            link("z"),
            link("y")
        ), FavoritesRepository.ImportMode.MERGE, "file")
        assertNull(result.folder)
        assertEquals(5, result.added)
        assertEquals(1, result.updated)
        assertEquals(2, result.skipped)
        assertEquals(listOf("t1", "t2"), titles(HOME_PAGE))
        //the folders of the Home page folder go to the top level
        assertEquals(listOf("Sub", "News", "y", "z"), titles(ROOT))
        assertEquals(listOf("New x", "n2", "n1"), titles(news.id))
        assertEquals(1, favorites.folders().count { it.title == "News" })
    }

    private suspend fun channel(source: Config.ChannelBookmarksSource, folder: Long = ROOT) =
        favorites.channelBookmarks(source, folder).map { it.title }

    /** top: a, b, folder f (c, folder g (d), and a again); home page: h1, h2 */
    private suspend fun channelTree(): Pair<FavoriteItem, FavoriteItem> {
        favorites.add(bookmark("h1"), HOME_PAGE)
        favorites.add(bookmark("h2"), HOME_PAGE)
        val f = folder("f").also { favorites.add(it, ROOT) }
        val g = folder("g").also { favorites.add(it, f.id) }
        favorites.add(bookmark("d"), g.id)
        favorites.add(FavoriteItem().apply { title = "a again"; url = "https://a.com/" }, f.id)
        favorites.add(bookmark("c"), f.id)
        favorites.add(bookmark("a"), ROOT)
        favorites.add(bookmark("b"), ROOT)
        return f to g
    }

    @Test
    fun channelOfTheHomePageHasItsBookmarksInTheirOrder() = runBlocking {
        channelTree()
        assertEquals(listOf("h1", "h2"), channel(Config.ChannelBookmarksSource.HOME_PAGE))
    }

    @Test
    fun channelOfAFolderLeavesOutItsFolders() = runBlocking {
        val (f, _) = channelTree()
        assertEquals(listOf("c", "a again"), channel(Config.ChannelBookmarksSource.FOLDER, f.id))
        assertEquals(listOf("b", "a"), channel(Config.ChannelBookmarksSource.FOLDER, ROOT))
    }

    @Test
    fun channelOfAGoneFolderShowsTheHomePage() = runBlocking {
        val (_, g) = channelTree()
        favorites.delete(g)
        assertEquals(listOf("h1", "h2"), channel(Config.ChannelBookmarksSource.FOLDER, g.id))
    }

    @Test
    fun channelOfAllWalksTheTreeAndKeepsEachAddressOnce() = runBlocking {
        channelTree()
        //each folder's bookmarks before its folders, then the home page; "a again" has the url of "a"
        assertEquals(listOf("b", "a", "c", "d", "h1", "h2"), channel(Config.ChannelBookmarksSource.ALL))
    }
}
