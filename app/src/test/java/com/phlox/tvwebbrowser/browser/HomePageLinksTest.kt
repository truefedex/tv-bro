package com.phlox.tvwebbrowser.browser

import androidx.room.Room
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class HomePageLinksTest {
    private lateinit var db: AppDatabase
    private val config get() = AppContext.provideConfig()
    private lateinit var links: HomePageLinks

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        AppDatabase.setInstanceForTests(db)
        links = HomePageLinks(config, TVBro.container.data.history, TVBro.container.data.favorites)
    }

    @After
    fun tearDown() {
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    private fun bookmark(title: String, order: Int, validUntil: Date? = null) = FavoriteItem().apply {
        this.title = title
        url = "https://$title.com/"
        this.order = order
        homePageBookmark = true
        this.validUntil = validUntil
    }

    private fun visit(url: String, time: Long) = runBlocking {
        db.historyDao().insert(HistoryItem().apply { this.url = url; title = url; this.time = time })
    }

    @Test
    fun firstStartSuggestsBookmarks() = runBlocking {
        links.load()
        assertEquals(8, links.links.value.size)
        assertTrue(config.initialBookmarksSuggestionsLoaded)
        assertEquals(8, db.favoritesDao().getHomePageBookmarks().size)
        assertEquals((0 until 8).toList(), links.links.value.map { it.order })
    }

    @Test
    fun suggestionsAreNotRepeatedAfterTheUserRemovedThem() = runBlocking {
        config.initialBookmarksSuggestionsLoaded = true
        links.load()
        assertTrue(links.links.value.isEmpty())
    }

    @Test
    fun savedBookmarksAreLoaded() = runBlocking {
        db.favoritesDao().insert(bookmark("a", 0))
        db.favoritesDao().insert(bookmark("b", 1))
        links.load()
        assertEquals(listOf("https://a.com/", "https://b.com/"), links.links.value.map { it.url })
    }

    @Test
    fun latestHistoryIsLoaded() = runBlocking {
        config.homePageLinksMode = Config.HomePageLinksMode.LATEST_HISTORY
        visit("https://old.com/", 1)
        visit("https://new.com/", 2)
        links.load()
        assertEquals(listOf("https://new.com/", "https://old.com/"), links.links.value.map { it.url })
    }

    @Test
    fun nothingIsLoadedForOtherHomePages() = runBlocking {
        config.homePageMode = Config.HomePageMode.SEARCH_ENGINE
        links.load()
        assertTrue(links.links.value.isEmpty())
        assertFalse(config.initialBookmarksSuggestionsLoaded)
    }

    @Test
    fun editedAndNewBookmarksUpdateTheLinks() = runBlocking {
        val a = bookmark("a", 0)
        a.id = db.favoritesDao().insert(a)
        links.load()

        a.title = "renamed"
        links.onBookmarkEdited(a)
        assertEquals(listOf("renamed"), links.links.value.map { it.title })
        assertEquals("renamed", db.favoritesDao().getById(a.id)!!.title)

        val b = bookmark("b", 1)
        links.onBookmarkEdited(b)
        assertTrue(b.id != 0L)
        assertEquals(listOf("renamed", "b"), links.links.value.map { it.title })
    }

    @Test
    fun linksTakeThePlacesOfTheFolderOrder() = runBlocking {
        db.favoritesDao().insert(bookmark("a", 0))
        //a gap left by an empty tile of older versions
        db.favoritesDao().insert(bookmark("c", 5))
        links.load()
        assertEquals(listOf(0, 1), links.links.value.map { it.order })

        //a new bookmark goes after the others
        links.onBookmarkEdited(FavoriteItem().apply { title = "new"; url = "https://new.com/" })
        assertEquals(listOf("a", "c", "new"), links.links.value.map { it.title })
        assertEquals(listOf(0, 1, 2), links.links.value.map { it.order })

        links.remove(links.links.value[0])
        assertEquals(listOf("c", "new"), links.links.value.map { it.title })
        assertEquals(listOf(0, 1), links.links.value.map { it.order })
    }

    private suspend fun folderWith(vararg titles: String): FavoriteItem {
        val favorites = TVBro.container.data.favorites
        val folder = FavoriteItem().apply { title = "folder" }
        favorites.add(folder, FavoritesRepository.ROOT)
        favorites.add(FavoriteItem().apply { title = "sub" }, folder.id)
        //added to the top one by one: the first title ends at the top
        for (title in titles.reversed()) {
            favorites.add(FavoriteItem().apply { this.title = title; url = "https://$title.com/" }, folder.id)
        }
        return folder
    }

    @Test
    fun aChosenFolderShowsItsBookmarksWithoutItsFolders() = runBlocking {
        db.favoritesDao().insert(bookmark("home", 0))
        val folder = folderWith("a", "b")
        config.homePageBookmarksFolder = folder.id
        links.load()
        assertEquals(listOf("a", "b"), links.links.value.map { it.title })
        assertEquals(listOf(0, 1), links.links.value.map { it.order })
    }

    @Test
    fun anEmptyChosenFolderGetsNoSuggestions() = runBlocking {
        val folder = folderWith()
        config.homePageBookmarksFolder = folder.id
        links.load()
        assertTrue(links.links.value.isEmpty())
        assertTrue(db.favoritesDao().getHomePageBookmarks().isEmpty())
        assertFalse(config.initialBookmarksSuggestionsLoaded)
    }

    @Test
    fun aGoneFolderShowsTheHomePageBookmarks() = runBlocking {
        config.initialBookmarksSuggestionsLoaded = true
        db.favoritesDao().insert(bookmark("home", 0))
        config.homePageBookmarksFolder = 99
        links.load()
        assertEquals(listOf("home"), links.links.value.map { it.title })

        //a new tile goes there too
        links.onBookmarkEdited(FavoriteItem().apply { title = "new"; url = "https://new.com/" })
        assertEquals(listOf("home", "new"), db.favoritesDao().getHomePageBookmarks().map { it.title })
    }

    @Test
    fun aNewTileGoesToTheEndOfTheChosenFolder() = runBlocking {
        val folder = folderWith("a", "b")
        config.homePageBookmarksFolder = folder.id
        links.load()
        val item = FavoriteItem().apply { title = "new"; url = "https://new.com/" }
        links.onBookmarkEdited(item)
        assertFalse(item.homePageBookmark)
        assertEquals(folder.id, item.parent)
        assertEquals(listOf("a", "b", "new"), links.links.value.map { it.title })
        assertTrue(db.favoritesDao().getHomePageBookmarks().isEmpty())

        links.remove(links.links.value[0])
        assertEquals(listOf("b", "new"), links.links.value.map { it.title })
    }

    @Test
    fun theTopLevelCanBeShown() = runBlocking {
        TVBro.container.data.favorites.add(FavoriteItem().apply { title = "top"; url = "https://top.com/" },
            FavoritesRepository.ROOT)
        config.homePageBookmarksFolder = FavoritesRepository.ROOT
        links.load()
        assertEquals(listOf("top"), links.links.value.map { it.title })
    }

    @Test
    fun removedLinkDeletesItsBookmark() = runBlocking {
        db.favoritesDao().insert(bookmark("a", 0))
        db.favoritesDao().insert(bookmark("b", 1))
        links.load()
        links.remove(links.links.value[0])
        assertEquals(listOf("https://b.com/"), links.links.value.map { it.url })
        assertEquals(listOf("https://b.com/"), db.favoritesDao().getHomePageBookmarks().map { it.url })
    }

    @Test
    fun onlyTemporaryRecommendationsAreMarkedAsUseful() = runBlocking {
        val permanent = bookmark("permanent", 0)
        permanent.id = db.favoritesDao().insert(permanent)
        val recommendation = bookmark("recommendation", 1, validUntil = Date(System.currentTimeMillis() + 100000))
        recommendation.id = db.favoritesDao().insert(recommendation)
        links.load()

        links.markRecommendationAsUseful(0)
        links.markRecommendationAsUseful(1)
        assertFalse(db.favoritesDao().getById(permanent.id)!!.useful)
        assertTrue(db.favoritesDao().getById(recommendation.id)!!.useful)
    }
}
