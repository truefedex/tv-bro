package com.phlox.tvwebbrowser.sync

import androidx.room.Room
import com.phlox.tvwebbrowser.data.BookmarksHtml
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.HOME_PAGE
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.sync.SyncBookmark.Folder
import com.phlox.tvwebbrowser.sync.SyncBookmark.Link
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BookmarksReplacerTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val favorites = DataRepositories(db).favorites
    private val replacer = BookmarksReplacer(favorites)

    @After
    fun tearDown() {
        db.close()
    }

    private fun url(name: String) = "https://$name.com/"

    private fun link(name: String, title: String = name, order: Int? = null) = Link(title, url(name), order)

    private suspend fun addBookmark(name: String, folder: Long, title: String = name): FavoriteItem =
        FavoriteItem().apply {
            this.title = title
            url = url(name)
            favorites.add(this, folder)
        }

    private suspend fun addFolder(title: String, folder: Long = ROOT): FavoriteItem =
        FavoriteItem().apply {
            this.title = title
            favorites.add(this, folder)
        }

    private suspend fun titles(folder: Long) = favorites.children(folder).map { it.title }

    private suspend fun folderNamed(title: String, parent: Long = ROOT) =
        favorites.children(parent).first { it.isFolder && it.title == title }.id

    /** Replaces after a dry run, which must tell the same changes and change nothing */
    private suspend fun replace(root: Folder): BookmarksReplacer.Changes {
        val before = favorites.exportTree("Home")
        val preview = replacer.replace(root, dryRun = true)
        assertEquals(before, favorites.exportTree("Home"))
        val changes = replacer.replace(root, dryRun = false)
        assertEquals(listOf(preview.added, preview.updated, preview.removed, preview.removedTitles),
            listOf(changes.added, changes.updated, changes.removed, changes.removedTitles))
        return changes
    }

    @Test
    fun aWholeFolderBecomesTheSame() = runBlocking {
        val news = addFolder("News")
        addBookmark("old", news.id)
        addBookmark("b", news.id, title = "B old")
        addBookmark("a", news.id)
        addFolder("Gone", news.id).also { addBookmark("deep", it.id) }
        addBookmark("other", ROOT)

        val changes = replace(Folder("", all = false, children = listOf(
            Folder(" news ", all = true, children = listOf(link("a"), link("b"), link("new")))
        )))

        assertEquals(1, changes.added)
        assertEquals(1, changes.updated)
        //"old" and "deep" in the deleted folder
        assertEquals(2, changes.removed)
        assertEquals(setOf("old", "Gone"), changes.removedTitles.toSet())
        assertEquals(listOf("a", "b", "new"), titles(news.id))
        //the folder kept its name and what is outside of it stays
        assertEquals(listOf("News", "other"), titles(ROOT))
    }

    @Test
    fun aFolderChosenInPartOnlyGetsNewAndUpdatedItems() = runBlocking {
        val news = addFolder("News")
        addBookmark("kept", news.id)
        addBookmark("a", news.id, title = "A old")

        val changes = replace(Folder("", all = false, children = listOf(
            Folder("News", all = false, children = listOf(link("x"), link("a"), link("y")))
        )))

        assertEquals(2, changes.added)
        assertEquals(1, changes.updated)
        assertEquals(0, changes.removed)
        //new ones at the top in the order of the phone
        assertEquals(listOf("x", "y", "a", "kept"), titles(news.id))
    }

    @Test
    fun newFoldersAreMadeWithAllTheirContents() = runBlocking {
        val changes = replace(Folder("", all = false, children = listOf(
            Folder("Music", all = true, children = listOf(
                Folder("Rock", all = true, children = listOf(link("r1"), link("r2"))),
                link("m1")
            ))
        )))
        assertEquals(3, changes.added)
        val music = folderNamed("Music")
        assertEquals(listOf("Rock", "m1"), titles(music))
        assertEquals(listOf("r1", "r2"), titles(folderNamed("Rock", music)))
    }

    @Test
    fun theSameAddressInAnotherFolderStaysThere() = runBlocking {
        val work = addFolder("Work")
        addBookmark("a", work.id)
        replace(Folder("", all = false, children = listOf(Folder("News", all = true, children = listOf(link("a"))))))
        assertEquals(listOf("a"), titles(work.id))
        assertEquals(listOf("a"), titles(folderNamed("News")))
    }

    @Test
    fun everythingChosenReplacesAllBookmarksAndTheHomePage() = runBlocking {
        addFolder("Old").also { addBookmark("o", it.id) }
        addBookmark("top", ROOT)
        addBookmark("h1", HOME_PAGE)
        addBookmark("h2", HOME_PAGE)

        val changes = replace(Folder("", all = true, children = listOf(
            Folder("Home page", all = true, homePage = true, children = emptyList()),
            link("top"), link("new")
        )))

        assertEquals(1, changes.added)
        assertEquals(3, changes.removed)
        assertEquals(listOf("top", "new"), titles(ROOT))
        assertTrue(favorites.children(HOME_PAGE).isEmpty())
    }

    @Test
    fun theWholeHomePageKeepsThePlacesOfThePhone() = runBlocking {
        val kept = addBookmark("a", HOME_PAGE).also { it.description = "kept" ; favorites.save(it) }
        addBookmark("gone", HOME_PAGE)

        replace(Folder("", all = false, children = listOf(
            Folder("Home page", all = true, homePage = true, children = listOf(link("b", order = 0), link("a", order = 5)))
        )))

        val home = favorites.children(HOME_PAGE)
        assertEquals(listOf("b", "a"), home.map { it.title })
        assertEquals(listOf(0, 5), home.map { it.order })
        //the bookmark of the TV stays with its data
        assertEquals(kept.id, home[1].id)
        assertEquals("kept", home[1].description)
    }

    @Test
    fun aHomePageChosenInPartGetsNewBookmarksAtItsEnd() = runBlocking {
        addBookmark("a", HOME_PAGE)
        replace(Folder("", all = false, children = listOf(
            Folder("Home page", all = false, homePage = true, children = listOf(link("b"), link("c")))
        )))
        assertEquals(listOf("a", "b", "c"), titles(HOME_PAGE))
    }

    @Test
    fun aBackupBringsBackTheBookmarks() = runBlocking {
        val news = addFolder("News")
        addBookmark("a", news.id)
        addBookmark("top", ROOT)
        addBookmark("h", HOME_PAGE)
        val before = favorites.exportTree("Home")

        replace(Folder("", all = true, children = listOf(link("other"))))
        val backup = BookmarksHtml.parse(BookmarksHtml.write(before))
        replacer.replace(SyncBookmark.everything(backup), dryRun = false)

        assertEquals(before, favorites.exportTree("Home"))
    }

    @Test
    fun theTreeGoesThroughJson() {
        val tree = Folder("", all = false, children = listOf(
            Folder("Home page", all = true, homePage = true, children = listOf(link("h", order = 3))),
            Folder("F", all = true, children = listOf(link("a"), Folder("G", all = false, children = emptyList()))),
            Link("bad", "javascript:alert(1)")
        ))
        val parsed = SyncBookmark.fromJson(SyncBookmark.toJson(tree))
        assertEquals(tree.copy(children = tree.children.dropLast(1)), parsed)
    }
}
