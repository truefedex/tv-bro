package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Saved engine state files of tabs: an engine must not restore the state saved by another one.
 */
@RunWith(RobolectricTestRunner::class)
class TabStateFilesTest {
    private val statesDir get() = File(AppContext.get().filesDir, WebTabState.TAB_WVSTATES_DIR)

    @Before
    fun setUp() {
        WebEngineFactory.initializeForTests(FakeWebEngine.provider)
    }

    private fun savedTab(prefix: String): WebTabState {
        val tab = WebTabState(url = "https://a.com/")
        tab.fakeEngine.stateFilePrefix = prefix
        tab.savedState = "state of a"
        tab.saveWebViewStateToFile()
        return tab
    }

    /** The same tab after the app restart: only the file name is known */
    private fun reloadedTab(saved: WebTabState, prefix: String): WebTabState {
        val tab = WebTabState(url = saved.url, wvStateFileName = saved.wvStateFileName)
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
        assertTrue(tab.restoreWebView())
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

        assertTrue(reloadedTab(saved, "gecko:").restoreWebView())
    }

    @Test
    fun stateOfAnotherEngineIsNotRestored() {
        val savedByGecko = savedTab("gecko:")
        assertFalse(reloadedTab(savedByGecko, "").restoreWebView())

        val savedByWebView = savedTab("")
        assertFalse(reloadedTab(savedByWebView, "gecko:").restoreWebView())
    }

    @Test
    fun stateOfAnotherEngineIsReplacedOnSave() {
        val savedByGecko = savedTab("gecko:")
        val oldFile = File(statesDir, savedByGecko.wvStateFileName!!.removePrefix("gecko:"))
        assertTrue(oldFile.exists())

        val tab = reloadedTab(savedByGecko, "")
        tab.savedState = "new state"
        tab.saveWebViewStateToFile()
        assertFalse(oldFile.exists())
        assertFalse(tab.wvStateFileName!!.contains(':'))
        assertTrue(reloadedTab(tab, "").restoreWebView())
    }

    @Test
    fun engineIsCreatedOnFirstAccess() {
        val tab = WebTabState(url = "https://a.com/")
        assertFalse(tab.isWebEngineCreated)
        tab.webEngine
        assertTrue(tab.isWebEngineCreated)
    }

    @Test
    fun tabWithoutStateFileHasNothingToRestore() {
        val tab = WebTabState(url = "https://a.com/")
        assertNull(tab.wvStateFileName)
        assertFalse(tab.restoreWebView())
    }
}
