package com.phlox.tvwebbrowser.data

import androidx.room.Room
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HostConfigRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repository = HostConfigRepository(db.hostsDao())

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun foundConfigIsCached() = runBlocking {
        repository.setPopupBlockLevel("a.com", HostConfig.POPUP_BLOCK_NONE)
        assertEquals(HostConfig.POPUP_BLOCK_NONE, repository.cached("a.com")!!.popupBlockLevel)
        assertNull(repository.cached("b.com"))
    }

    @Test
    fun missingConfigIsCachedToo() = runBlocking {
        assertNull(repository.find("a.com"))
        //written around the repository: the cached absence is kept, no second query
        db.hostsDao().setPopupBlockLevel("a.com", HostConfig.POPUP_BLOCK_NONE)
        assertNull(repository.find("a.com"))
    }

    @Test
    fun settingsDoNotOverwriteEachOther() = runBlocking {
        repository.setFavicon("a.com", "a.png")
        repository.setPopupBlockLevel("a.com", HostConfig.POPUP_BLOCK_DIALOGS)
        repository.setFavicon("a.com", "a2.png")
        val config = repository.find("a.com")!!
        assertEquals("a2.png", config.favicon)
        assertEquals(HostConfig.POPUP_BLOCK_DIALOGS, config.popupBlockLevel)
        //one row per host
        assertEquals(config.id, db.hostsDao().findByHostName("a.com")!!.id)
    }

    @Test
    fun configWrittenByAnotherRepositoryIsNotLost() = runBlocking {
        //like FaviconsPool and the popup blocking menu writing at the same time
        val other = HostConfigRepository(db.hostsDao())
        repository.find("a.com")
        other.setPopupBlockLevel("a.com", HostConfig.POPUP_BLOCK_NONE)
        repository.setFavicon("a.com", "a.png")
        val stored = db.hostsDao().findByHostName("a.com")!!
        assertEquals(HostConfig.POPUP_BLOCK_NONE, stored.popupBlockLevel)
        assertEquals("a.png", stored.favicon)
    }
}
