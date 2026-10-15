package com.phlox.tvwebbrowser.data

import androidx.room.Room
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.TabEntity
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class DataRepositoriesTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val data = DataRepositories(db)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savedSelectedTabUnselectsTheOthers() = runBlocking {
        val a = data.tabs.save(TabEntity(url = "https://a.com/", selected = true, position = 0))
        val b = data.tabs.save(TabEntity(url = "https://b.com/", selected = true, position = 1))
        assertEquals(mapOf(a to false, b to true), data.tabs.load(incognito = false).associate { it.id to it.selected })

        //an update keeps the id
        assertEquals(a, data.tabs.save(TabEntity(id = a, url = "https://a2.com/", selected = true, position = 0)))
        assertEquals(mapOf(a to true, b to false), data.tabs.load(incognito = false).associate { it.id to it.selected })
    }

    @Test
    fun tabSelectionIsKeptPerMode() = runBlocking {
        val incognito = data.tabs.save(TabEntity(url = "https://i.com/", selected = true, incognito = true))
        data.tabs.save(TabEntity(url = "https://a.com/", selected = true))
        assertEquals(true, data.tabs.load(incognito = true).single { it.id == incognito }.selected)
    }

    @Test
    fun savedBookmarkGetsItsId() = runBlocking {
        val item = FavoriteItem().apply { title = "A"; url = "https://a.com/" }
        data.favorites.save(item)
        assertNotEquals(0L, item.id)
        item.title = "A2"
        data.favorites.save(item)
        assertEquals(listOf("A2"), data.favorites.all().map { it.title })
    }

    @Test
    fun historyIsTrimmedOnlyWhenTooLong() = runBlocking {
        val old = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis
        fun item(time: Long) = HistoryItem().apply { url = "https://a.com/"; title = ""; this.time = time }
        data.history.insert(item(old))
        data.history.trim()
        assertEquals(1, data.history.last(10).size)

        repeat(5000) { data.history.insert(item(System.currentTimeMillis())) }
        data.history.trim()
        //the old item went, the recent ones stay
        assertEquals(5000, db.historyDao().count())
        assertEquals(0, data.history.page(0).count { it.time == old })
    }
}
