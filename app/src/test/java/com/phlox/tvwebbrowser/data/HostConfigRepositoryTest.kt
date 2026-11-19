package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.webengine.SiteView
import androidx.room.Room
import com.phlox.tvwebbrowser.model.HostConfig
import com.phlox.tvwebbrowser.model.dao.HostsDao
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
    fun adBlockSettingsOfAllHostsAreLoaded() = runBlocking {
        db.hostsDao().setAdBlock("a.com", false)
        db.hostsDao().setAdBlock("b.com", true)
        db.hostsDao().setPopupBlockLevel("c.com", HostConfig.POPUP_BLOCK_NONE)
        assertNull(repository.adBlockSetting("a.com"))
        repository.loadSiteSettings()
        assertEquals(false, repository.adBlockSetting("a.com"))
        assertEquals(true, repository.adBlockSetting("b.com"))
        assertNull(repository.adBlockSetting("c.com"))
    }

    @Test
    fun adBlockSettingIsStoredWithTheOtherSettings() = runBlocking {
        repository.setPopupBlockLevel("a.com", HostConfig.POPUP_BLOCK_DIALOGS)
        repository.setAdBlock("a.com", false)
        assertEquals(false, repository.adBlockSetting("a.com"))
        val stored = db.hostsDao().findByHostName("a.com")!!
        assertEquals(false, stored.adblock)
        assertEquals(HostConfig.POPUP_BLOCK_DIALOGS, stored.popupBlockLevel)

        repository.setAdBlock("a.com", null)
        assertNull(repository.adBlockSetting("a.com"))
        assertNull(db.hostsDao().findByHostName("a.com")!!.adblock)
        assertEquals(emptyList<HostsDao.AdBlockSetting>(), db.hostsDao().adBlockSettings())
    }

    @Test
    fun siteViewSettingsOfAllHostsAreLoaded() = runBlocking {
        db.hostsDao().setSiteView("a.com", SiteView.DESKTOP.code)
        db.hostsDao().setSiteView("b.com", SiteView.MOBILE.code)
        db.hostsDao().setAdBlock("c.com", false)
        assertNull(repository.siteViewSetting("a.com"))
        repository.loadSiteSettings()
        assertEquals(SiteView.DESKTOP, repository.siteViewSetting("a.com"))
        assertEquals(SiteView.MOBILE, repository.siteViewSetting("b.com"))
        assertNull(repository.siteViewSetting("c.com"))
        assertEquals(false, repository.adBlockSetting("c.com"))
    }

    @Test
    fun siteViewSettingIsStoredWithTheOtherSettings() = runBlocking {
        repository.setAdBlock("a.com", false)
        repository.setSiteView("a.com", SiteView.DESKTOP)
        assertEquals(SiteView.DESKTOP, repository.siteViewSetting("a.com"))
        val stored = db.hostsDao().findByHostName("a.com")!!
        assertEquals(SiteView.DESKTOP.code, stored.siteView)
        assertEquals(false, stored.adblock)

        repository.setSiteView("a.com", null)
        assertNull(repository.siteViewSetting("a.com"))
        assertNull(db.hostsDao().findByHostName("a.com")!!.siteView)
        assertEquals(false, repository.adBlockSetting("a.com"))
        assertEquals(emptyList<HostsDao.SiteViewSetting>(), db.hostsDao().siteViewSettings())
    }

    @Test
    fun playerSettingsOfAllHostsAreLoadedAndStoredWithTheOtherSettings() = runBlocking {
        db.hostsDao().setBuiltInPlayer("a.com", false)
        db.hostsDao().setSiteView("a.com", SiteView.DESKTOP.code)
        assertNull(repository.builtInPlayerSetting("a.com"))
        repository.loadSiteSettings()
        assertEquals(false, repository.builtInPlayerSetting("a.com"))
        assertEquals(SiteView.DESKTOP, repository.siteViewSetting("a.com"))

        repository.setBuiltInPlayer("b.com", true)
        assertEquals(true, repository.builtInPlayerSetting("b.com"))
        assertEquals(true, db.hostsDao().findByHostName("b.com")!!.builtInPlayer)
        //back to the default: the other settings of the host stay
        repository.setBuiltInPlayer("a.com", null)
        assertNull(repository.builtInPlayerSetting("a.com"))
        val stored = db.hostsDao().findByHostName("a.com")!!
        assertNull(stored.builtInPlayer)
        assertEquals(SiteView.DESKTOP.code, stored.siteView)
        assertEquals(listOf(HostsDao.BuiltInPlayerSetting("b.com", true)), db.hostsDao().builtInPlayerSettings())
    }

    @Test
    fun pageZoomsOfAllHostsAreLoadedAndStoredWithTheOtherSettings() = runBlocking {
        db.hostsDao().setPageZoom("a.com", 80)
        db.hostsDao().setSiteView("a.com", SiteView.DESKTOP.code)
        assertNull(repository.pageZoomSetting("a.com"))
        repository.loadSiteSettings()
        assertEquals(80, repository.pageZoomSetting("a.com"))

        repository.setPageZoom("b.com", 67)
        assertEquals(67, repository.pageZoomSetting("b.com"))
        assertEquals(67, db.hostsDao().findByHostName("b.com")!!.pageZoom)
        //back to 100%: the other settings of the host stay
        repository.setPageZoom("a.com", null)
        assertNull(repository.pageZoomSetting("a.com"))
        val stored = db.hostsDao().findByHostName("a.com")!!
        assertNull(stored.pageZoom)
        assertEquals(SiteView.DESKTOP.code, stored.siteView)
        assertEquals(listOf(HostsDao.PageZoomSetting("b.com", 67)), db.hostsDao().pageZoomSettings())
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
