package com.phlox.tvwebbrowser.data

import androidx.room.Room
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
}
