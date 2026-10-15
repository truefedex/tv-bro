package com.phlox.tvwebbrowser.browser

import android.os.Looper
import androidx.room.Room
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BrowserControllerTest {
    private lateinit var db: AppDatabase
    private val container get() = TVBro.instance.container

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppDatabase.setInstanceForTests(db)
    }

    @After
    fun tearDown() {
        AppDatabase.setInstanceForTests(null)
        db.close()
    }

    /** Coroutines resume on the main looper, which is paused in Robolectric tests */
    private fun Job.awaitOnMainLooper() {
        val deadline = System.currentTimeMillis() + 10_000
        while (!isCompleted) {
            check(System.currentTimeMillis() < deadline) { "timeout" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
    }

    private fun awaitWrites() = container.appScope.launch { container.tabWrites.awaitPending() }.awaitOnMainLooper()

    private fun storedTabs(): List<BrowserTab> = db.query("SELECT url FROM tabs ORDER BY id", null).use { c ->
        generateSequence { if (c.moveToNext()) BrowserTab(url = c.getString(0)) else null }.toList()
    }

    @Test
    fun repeatedSaveOfNewTabInsertsOneRow() {
        val model = BrowserController()
        val tab = BrowserTab(url = "https://example.com/", selected = true)
        model.saveTab(tab)
        model.saveTab(tab)
        awaitWrites()
        assertEquals(listOf("https://example.com/"), storedTabs().map { it.url })
        assertNotEquals(0L, tab.id)
    }

    @Test
    fun saveIsNotCancelledWhenModelIsCleared() {
        val model = BrowserController()
        model.saveTab(BrowserTab(url = "https://example.com/"))
        model.clear()
        awaitWrites()
        assertEquals(1, storedTabs().size)
    }

    @Test
    fun loadStateWaitsForPendingSaves() {
        val previousModel = BrowserController()
        previousModel.saveTab(BrowserTab(url = "https://a.com/"))
        previousModel.saveTab(BrowserTab(url = "https://b.com/"))
        previousModel.clear()

        val model = BrowserController()
        model.loadState().awaitOnMainLooper()
        assertEquals(listOf("https://a.com/", "https://b.com/"), model.tabsStates.map { it.url })
    }

    @Test
    fun closingATabRightAfterItsFirstSaveDeletesTheRow() {
        val model = BrowserController()
        val keep = BrowserTab(url = "https://a.com/")
        val closed = BrowserTab(url = "https://b.com/")
        model.saveTab(keep)
        model.saveTab(closed)
        //the id is not known yet: it is taken when the delete runs
        model.onCloseTab(closed)
        awaitWrites()
        assertEquals(listOf("https://a.com/"), storedTabs().map { it.url })
    }

    @Test
    fun tabIsStoredAsItWasWhenSaved() {
        val model = BrowserController()
        val tab = BrowserTab(url = "https://a.com/")
        model.saveTab(tab)
        //an engine may report a transient page (for example about:blank) before the write runs
        tab.url = "about:blank"
        awaitWrites()
        assertEquals(listOf("https://a.com/"), storedTabs().map { it.url })
    }

    @Test
    fun closeAllTabsRunsAfterPendingSaves() {
        val model = BrowserController()
        model.saveTab(BrowserTab(url = "https://a.com/"))
        model.saveTab(BrowserTab(url = "https://b.com/"))
        model.onCloseAllTabs().awaitOnMainLooper()
        awaitWrites()
        assertTrue(storedTabs().isEmpty())
    }
}
