package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.browser.VisitedHistoryLogger.Companion.MIN_VISITED_INTERVAL
import com.phlox.tvwebbrowser.model.HistoryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VisitedHistoryLoggerTest {
    private class FakeStore : VisitedHistoryLogger.Store {
        val inserted = mutableListOf<HistoryItem>()
        val titleUpdates = mutableListOf<Pair<Long, String>>()

        override suspend fun insert(item: HistoryItem): Long {
            inserted.add(item)
            return inserted.size.toLong()
        }

        override suspend fun updateTitle(id: Long, title: String) {
            titleUpdates.add(id to title)
        }
    }

    private val store = FakeStore()

    private fun TestScope.logger() = VisitedHistoryLogger(this, store) { testScheduler.currentTime }

    private val urls get() = store.inserted.map { it.url }

    @Test
    fun visitIsSavedAfterInterval() = runTest {
        val logger = logger()
        logger.logVisited("Example", "https://example.com/", "hash")

        advanceTimeBy(MIN_VISITED_INTERVAL - 1)
        assertTrue(store.inserted.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, store.inserted.size)
        val item = store.inserted[0]
        assertEquals("https://example.com/", item.url)
        assertEquals("Example", item.title)
        assertEquals("hash", item.favicon)
        assertEquals(0L, item.time)
        assertEquals(1L, item.id)
        assertTrue(item.saved)
        assertTrue(item === logger.lastHistoryItem)
    }

    @Test
    fun quickNextVisitDropsPreviousOne() = runTest {
        val logger = logger()
        logger.logVisited("A", "https://a.com/", null)
        advanceTimeBy(1000)
        logger.logVisited("B", "https://b.com/", null)
        advanceUntilIdle()
        assertEquals(listOf("https://b.com/"), urls)
    }

    @Test
    fun chainOfQuickVisitsSavesOnlyTheLastOne() = runTest {
        val logger = logger()
        logger.logVisited(null, "https://a.com/", null)
        advanceTimeBy(MIN_VISITED_INTERVAL - 1)
        logger.logVisited(null, "https://b.com/", null)
        advanceTimeBy(MIN_VISITED_INTERVAL - 1)
        logger.logVisited(null, "https://c.com/", null)
        advanceUntilIdle()
        assertEquals(listOf("https://c.com/"), urls)
    }

    @Test
    fun slowVisitsAreAllSaved() = runTest {
        val logger = logger()
        logger.logVisited(null, "https://a.com/", null)
        advanceTimeBy(MIN_VISITED_INTERVAL + 1000)
        logger.logVisited(null, "https://b.com/", null)
        advanceUntilIdle()
        assertEquals(listOf("https://a.com/", "https://b.com/"), urls)
    }

    // current behavior: at exactly the interval the previous save is not cancelled even if not done yet
    @Test
    fun visitExactlyAtIntervalKeepsPreviousOne() = runTest {
        val logger = logger()
        logger.logVisited(null, "https://a.com/", null)
        advanceTimeBy(MIN_VISITED_INTERVAL)
        logger.logVisited(null, "https://b.com/", null)
        advanceUntilIdle()
        assertEquals(listOf("https://a.com/", "https://b.com/"), urls)
    }

    @Test
    fun sameUrlAsLastIsIgnored() = runTest {
        val logger = logger()
        logger.logVisited(null, "https://a.com/", null)
        advanceUntilIdle()
        logger.logVisited(null, "https://a.com/", null)
        advanceUntilIdle()
        logger.logVisited(null, "https://b.com/", null)
        advanceUntilIdle()
        logger.logVisited(null, "https://a.com/", null)
        advanceUntilIdle()
        assertEquals(listOf("https://a.com/", "https://b.com/", "https://a.com/"), urls)
    }

    @Test
    fun nonWebAndHomePagesAreIgnored() = runTest {
        val logger = logger()
        logger.logVisited(null, Config.HOME_PAGE_URL, null)
        logger.logVisited(null, Config.HOME_URL_ALIAS, null)
        logger.logVisited(null, "about:blank", null)
        logger.logVisited(null, "file:///sdcard/a.html", null)
        logger.logVisited(null, "data:text/html,hi", null)
        advanceUntilIdle()
        assertTrue(store.inserted.isEmpty())

        logger.logVisited(null, "HTTP://EXAMPLE.COM/", null)
        advanceUntilIdle()
        assertEquals(listOf("HTTP://EXAMPLE.COM/"), urls)
    }

    @Test
    fun missingTitleIsSavedAsEmpty() = runTest {
        val logger = logger()
        logger.logVisited(null, "https://a.com/", null)
        advanceUntilIdle()
        assertEquals("", store.inserted[0].title)
    }

    @Test
    fun titleUpdateBeforeSaveGoesIntoInsertedItem() = runTest {
        val logger = logger()
        logger.logVisited("Loading", "https://a.com/", null)
        advanceTimeBy(1000)
        logger.onTitleUpdated("https://a.com/", "Real title")
        advanceUntilIdle()
        assertEquals("Real title", store.inserted[0].title)
        assertTrue(store.titleUpdates.isEmpty())
    }

    @Test
    fun titleUpdateAfterSaveUpdatesStoredItem() = runTest {
        val logger = logger()
        logger.logVisited("Loading", "https://a.com/", null)
        advanceUntilIdle()
        logger.onTitleUpdated("https://a.com/", "Real title")
        advanceUntilIdle()
        assertEquals(listOf(1L to "Real title"), store.titleUpdates)
    }

    @Test
    fun titleUpdateForOtherUrlIsIgnored() = runTest {
        val logger = logger()
        logger.logVisited("A", "https://a.com/", null)
        advanceUntilIdle()
        logger.onTitleUpdated("https://b.com/", "B")
        advanceUntilIdle()
        assertEquals("A", logger.lastHistoryItem?.title)
        assertTrue(store.titleUpdates.isEmpty())
    }

    @Test
    fun titleUpdateWithoutVisitsIsIgnored() = runTest {
        val logger = logger()
        logger.onTitleUpdated("https://a.com/", "A")
        advanceUntilIdle()
        assertTrue(store.titleUpdates.isEmpty())
    }

    // current behavior: the last item loaded from the database has saved == false,
    // so a title update for it changes only the object in memory
    @Test
    fun itemLoadedFromDatabase() = runTest {
        val logger = logger()
        val fromDb = HistoryItem().apply { id = 10; url = "https://a.com/"; title = "A"; time = 1 }
        logger.lastHistoryItem = fromDb

        logger.logVisited(null, "https://a.com/", null)
        logger.onTitleUpdated("https://a.com/", "New A")
        advanceUntilIdle()
        assertTrue(store.inserted.isEmpty())
        assertTrue(store.titleUpdates.isEmpty())
        assertEquals("New A", fromDb.title)
        assertFalse(fromDb.saved)
    }
}
