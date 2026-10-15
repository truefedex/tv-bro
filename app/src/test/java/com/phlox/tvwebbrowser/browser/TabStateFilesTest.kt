package com.phlox.tvwebbrowser.browser

import android.graphics.Bitmap
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Files of tabs: saved engine states (an engine must not restore the state saved by another one) and thumbnails.
 */
@RunWith(RobolectricTestRunner::class)
class TabStateFilesTest {
    private val store = TabFilesStore(AppContext.get().filesDir, AppContext.get().cacheDir)
    private val statesDir get() = File(AppContext.get().filesDir, TabFilesStore.ENGINE_STATES_DIR)
    private val thumbnailsDir get() = File(AppContext.get().cacheDir, TabFilesStore.THUMBNAILS_DIR)

    @Before
    fun setUp() {
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
    }

    //what the controller does with the tab when it writes the tab's files
    private fun TabFilesStore.saveEngineState(tab: BrowserTab) {
        tab.wvStateFileName = writeEngineState(tab.savedState, tab.webEngine.stateFilePrefix, tab.wvStateFileName)
    }

    private fun TabFilesStore.saveThumbnail(tab: BrowserTab, thumbnail: Bitmap) {
        tab.thumbnailHash = writeThumbnail(thumbnail, tab.thumbnailHash)
    }

    private fun TabFilesStore.removeFiles(tab: BrowserTab) {
        removeFiles(tab.wvStateFileName, tab.thumbnailHash)
        tab.wvStateFileName = null
        tab.thumbnailHash = null
    }

    private fun TabFilesStore.loadThumbnail(tab: BrowserTab) = tab.thumbnailHash?.let { loadThumbnail(it) }

    private fun savedTab(prefix: String): BrowserTab {
        val tab = BrowserTab(url = "https://a.com/")
        tab.fakeEngine.stateFilePrefix = prefix
        tab.savedState = "state of a"
        store.saveEngineState(tab)
        return tab
    }

    /** The same tab after the app restart: only the file name is known */
    private fun reloadedTab(saved: BrowserTab, prefix: String): BrowserTab {
        val tab = BrowserTab(url = saved.url, wvStateFileName = saved.wvStateFileName)
        tab.fakeEngine.stateFilePrefix = prefix
        return tab
    }

    @Test
    fun engineWithoutPrefixRestoresItsState() {
        val saved = savedTab("")
        val fileName = saved.wvStateFileName!!
        assertFalse(fileName.contains(':'))
        assertTrue(File(statesDir, fileName).exists())

        val tab = reloadedTab(saved, "")
        assertTrue(store.restoreEngineState(tab))
        assertEquals("state of a", tab.savedState)
        assertEquals("restoreState", tab.fakeEngine.log.last())
    }

    @Test
    fun engineWithPrefixRestoresItsState() {
        val saved = savedTab("gecko:")
        val fileName = saved.wvStateFileName!!
        assertTrue(fileName.startsWith("gecko:"))
        //the prefix is not a part of the file path
        assertTrue(File(statesDir, fileName.removePrefix("gecko:")).exists())

        assertTrue(store.restoreEngineState(reloadedTab(saved, "gecko:")))
    }

    @Test
    fun stateOfAnotherEngineIsNotRestored() {
        val savedByGecko = savedTab("gecko:")
        assertFalse(store.restoreEngineState(reloadedTab(savedByGecko, "")))

        val savedByWebView = savedTab("")
        assertFalse(store.restoreEngineState(reloadedTab(savedByWebView, "gecko:")))
    }

    @Test
    fun stateOfAnotherEngineIsReplacedOnSave() {
        val savedByGecko = savedTab("gecko:")
        val oldFile = File(statesDir, savedByGecko.wvStateFileName!!.removePrefix("gecko:"))
        assertTrue(oldFile.exists())

        val tab = reloadedTab(savedByGecko, "")
        tab.savedState = "new state"
        store.saveEngineState(tab)
        assertFalse(oldFile.exists())
        assertFalse(tab.wvStateFileName!!.contains(':'))
        assertTrue(store.restoreEngineState(reloadedTab(tab, "")))
    }

    @Test
    fun engineIsCreatedOnFirstAccess() {
        val tab = BrowserTab(url = "https://a.com/")
        assertFalse(tab.isWebEngineCreated)
        tab.webEngine
        assertTrue(tab.isWebEngineCreated)
    }

    @Test
    fun tabWithoutStateFileHasNothingToRestore() {
        val tab = BrowserTab(url = "https://a.com/")
        assertNull(tab.wvStateFileName)
        assertFalse(store.restoreEngineState(tab))
    }

    private fun thumbnail() = Bitmap.createBitmap(32, 18, Bitmap.Config.RGB_565)

    private fun thumbnailFiles() = thumbnailsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

    @Test
    fun tabsWithTheSameUrlHaveTheirOwnThumbnails() {
        val a = BrowserTab(url = "https://a.com/")
        val b = BrowserTab(url = "https://a.com/")
        store.saveThumbnail(a, thumbnail())
        store.saveThumbnail(b, thumbnail())
        assertNotEquals(a.thumbnailHash, b.thumbnailHash)

        store.removeFiles(a)
        assertNull(a.thumbnailHash)
        assertNotNull(store.loadThumbnail(b))
    }

    @Test
    fun newThumbnailReplacesTheOldFile() {
        val tab = BrowserTab(url = "https://a.com/")
        store.saveThumbnail(tab, thumbnail())
        val first = tab.thumbnailHash!!
        store.saveThumbnail(tab, thumbnail())
        val second = tab.thumbnailHash!!
        assertNotEquals(first, second)
        assertTrue(second in thumbnailFiles())
        assertFalse(first in thumbnailFiles())
    }

    @Test
    fun thumbnailOfTheOldFormatIsLoaded() {
        thumbnailsDir.mkdirs()
        File(thumbnailsDir, "oldhash.png").outputStream().use {
            thumbnail().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val tab = BrowserTab(url = "https://a.com/", thumbnailHash = "oldhash")
        assertNotNull(store.loadThumbnail(tab))
        store.removeFiles(tab)
        assertFalse("oldhash.png" in thumbnailFiles())
    }

    @Test
    fun onlyAnUpdatedThumbnailIsTakenForSaving() {
        val tab = BrowserTab(url = "https://a.com/")
        assertNull(tab.takeChangedThumbnail())
        val bitmap = thumbnail()
        tab.updateThumbnail(bitmap)
        assertSame(bitmap, tab.takeChangedThumbnail())
        assertNull(tab.takeChangedThumbnail())
    }
}
