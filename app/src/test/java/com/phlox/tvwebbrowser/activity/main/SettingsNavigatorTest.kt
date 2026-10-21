package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Category
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Key
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Row
import com.phlox.tvwebbrowser.activity.main.SettingsNavigator.Screen
import com.phlox.tvwebbrowser.singleton.shortcuts.KeyBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsNavigatorTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeStore : SettingsNavigator.Store {
        override var startupValues = SettingsNavigator.StartupValues(Config.InterfaceMode.AUTO, Config.Theme.SYSTEM,
            darkening = false, webEngineDebug = false)
        override var searchEngineUrl = Config.SearchEnginesURLs[0]
        override var homePageMode = Config.HomePageMode.HOME_PAGE
        override var homePageUrl = Config.HOME_URL_ALIAS
        override var homePageLinksMode = Config.HomePageLinksMode.BOOKMARKS
        override fun setHomePage(mode: Config.HomePageMode, customUrl: String?, linksMode: Config.HomePageLinksMode) {
            homePageMode = mode
            homePageLinksMode = linksMode
            homePageUrl = if (mode == Config.HomePageMode.CUSTOM) customUrl ?: Config.HOME_URL_ALIAS else Config.HOME_URL_ALIAS
        }
        override var defaultDesktopView: Boolean? = null
        override var allowAutoplayMedia = false
        override var adBlockEnabled = true
        override var filterListUrl = Config.DEFAULT_ADBLOCK_LIST_URL
        override var filterListLastUpdate = 0L
        override val filterListLoading = MutableStateFlow(false)
        var filterListUpdates = 0
        override fun updateFilterList() { filterListUpdates++ }
        override var interfaceMode = Config.InterfaceMode.AUTO
        override var theme = Config.Theme.SYSTEM
        override var supportsDarkening = true
        override var darkening = false
        override var keepScreenOn = false
        override var matchFrameRate = false
        override var joystickNavigation = true
        override var cursorSpeed = 100
        override var cursorAcceleration = 100
        override fun bindingOf(shortcut: Shortcut) = shortcut.defaultBinding
        var cacheCleared = false
        override suspend fun clearCache() { cacheCleared = true }
        override var webEngineDebug = false
        override val versionName = "2.2.0"
        override val buildName = "generic"
        override val engineVersion = "WebView 1.0"
        override var builtInUpdates = true
        override var autoCheckUpdates = true
        override var updateChannel = "release"
        var updates: SettingsNavigator.Updates? = SettingsNavigator.Updates(listOf("release", "beta"), null)
        val checks = ArrayList<Boolean>()
        override suspend fun checkUpdates(force: Boolean): SettingsNavigator.Updates? {
            checks += force
            return updates
        }
    }

    private val store = FakeStore()
    private lateinit var screen: Screen
    private val log = ArrayList<String>()
    private var confirmAnswer = true

    private val host = object : SettingsNavigator.Host {
        override fun render(screen: Screen) { this@SettingsNavigatorTest.screen = screen }
        override fun showMessage(icon: Int, message: String) { log += "message $message" }
        override fun confirm(title: String?, message: String, positive: String, onResult: (Boolean) -> Unit) {
            log += "confirm"
            onResult(confirmAnswer)
        }
        override fun captureKey(shortcut: Shortcut, onDone: () -> Unit) {
            log += "capture $shortcut"
            onDone()
        }
        override fun open(url: String) { log += "open $url" }
        override fun startUpdate() { log += "update" }
        override fun restart() { log += "restart" }
    }

    private fun navigator(tvUi: Boolean) =
        SettingsNavigator(context, store, CoroutineScope(Dispatchers.Unconfined), host, tvUi)

    private fun rows(): List<Row> = screen.sections.flatMap { it.rows }
    private fun keys(): List<Key> = rows().map { it.key }
    private inline fun <reified T : Row> row(key: Key): T = rows().first { it.key == key } as T

    @Test
    fun tvOpensTheFirstCategoryAndHasTheRemote() {
        val navigator = navigator(tvUi = true)
        navigator.show()
        assertEquals(Category.SEARCH, screen.page)
        assertTrue(screen.categories.any { it.category == Category.REMOTE })
        assertFalse(navigator.back())
    }

    @Test
    fun phoneStartsWithTheListAndHasNoRemote() {
        val navigator = navigator(tvUi = false)
        navigator.show()
        assertNull(screen.page)
        assertTrue(screen.sections.isEmpty())
        assertFalse(screen.categories.any { it.category == Category.REMOTE })
        navigator.open(Category.SITES)
        assertEquals(Category.SITES, screen.page)
        assertTrue(navigator.back())
        assertNull(screen.page)
        assertFalse(navigator.back())
    }

    @Test
    fun customSearchEngineShowsItsAddress() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.SEARCH)
        assertFalse(Key.SEARCH_URL in keys())
        navigator.choose(Key.SEARCH_ENGINE, SettingsNavigator.CUSTOM_SEARCH_ENGINE)
        //the address of the engine changes only when one is typed
        assertEquals(Config.SearchEnginesURLs[0], store.searchEngineUrl)
        assertEquals("", row<Row.Field>(Key.SEARCH_URL).text)
        navigator.setText(Key.SEARCH_URL, "  https://example.com/?q=[query] ")
        assertEquals("https://example.com/?q=[query]", store.searchEngineUrl)
        assertEquals(SettingsNavigator.CUSTOM_SEARCH_ENGINE, row<Row.Choice>(Key.SEARCH_ENGINE).selected)
        assertEquals("https://example.com/?q=[query]", row<Row.Field>(Key.SEARCH_URL).text)
        navigator.choose(Key.SEARCH_ENGINE, 3)
        assertEquals(Config.SearchEnginesURLs[3], store.searchEngineUrl)
        assertFalse(Key.SEARCH_URL in keys())
    }

    @Test
    fun homePageModeShowsItsOwnRow() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.SEARCH)
        assertTrue(Key.HOME_PAGE_LINKS in keys())
        assertFalse(Key.HOME_PAGE_URL in keys())
        navigator.choose(Key.HOME_PAGE, Config.HomePageMode.CUSTOM.ordinal)
        assertFalse(Key.HOME_PAGE_LINKS in keys())
        assertEquals("", row<Row.Field>(Key.HOME_PAGE_URL).text)
        //no address yet: the home page stays the built-in one
        assertEquals(Config.HOME_URL_ALIAS, store.homePageUrl)
        navigator.setText(Key.HOME_PAGE_URL, "https://example.com")
        assertEquals("https://example.com", store.homePageUrl)
        navigator.choose(Key.HOME_PAGE, Config.HomePageMode.BLANK.ordinal)
        assertFalse(Key.HOME_PAGE_LINKS in keys())
        assertFalse(Key.HOME_PAGE_URL in keys())
        navigator.choose(Key.HOME_PAGE, Config.HomePageMode.HOME_PAGE.ordinal)
        navigator.choose(Key.HOME_PAGE_LINKS, Config.HomePageLinksMode.MOST_VISITED.ordinal)
        assertEquals(Config.HomePageLinksMode.MOST_VISITED, store.homePageLinksMode)
    }

    @Test
    fun autoSiteViewIsNoSetting() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.SITES)
        assertEquals(SettingsNavigator.SITE_VIEW_AUTO, row<Row.Choice>(Key.SITE_VIEW).selected)
        navigator.choose(Key.SITE_VIEW, SettingsNavigator.SITE_VIEW_MOBILE_OPTION)
        assertEquals(false, store.defaultDesktopView)
        navigator.choose(Key.SITE_VIEW, SettingsNavigator.SITE_VIEW_DESKTOP_OPTION)
        assertEquals(true, store.defaultDesktopView)
        navigator.choose(Key.SITE_VIEW, SettingsNavigator.SITE_VIEW_AUTO)
        assertNull(store.defaultDesktopView)
    }

    @Test
    fun filterListOnlyWhileAdsAreBlocked() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.SITES)
        assertTrue(Key.FILTER_LIST in keys())
        navigator.act(row<Row.Field>(Key.FILTER_LIST))
        assertEquals(1, store.filterListUpdates)
        store.filterListLoading.value = true
        assertTrue(row<Row.Field>(Key.FILTER_LIST).busy)
        //no second load while one runs
        navigator.act(row<Row.Field>(Key.FILTER_LIST))
        assertEquals(1, store.filterListUpdates)
        navigator.toggle(Key.AD_BLOCK)
        assertFalse(store.adBlockEnabled)
        assertFalse(Key.FILTER_LIST in keys())
    }

    @Test
    fun darkeningOnlyWhenTheEngineSupportsIt() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.APPEARANCE)
        assertTrue(Key.DARKENING in keys())
        store.supportsDarkening = false
        navigator.show(Category.APPEARANCE)
        assertFalse(Key.DARKENING in keys())
    }

    @Test
    fun theFrameRateOfVideosIsMatchedOnTvOnly() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.APPEARANCE)
        assertFalse(row<Row.Toggle>(Key.MATCH_FRAME_RATE).checked)
        navigator.toggle(Key.MATCH_FRAME_RATE)
        assertTrue(store.matchFrameRate)
        assertTrue(row<Row.Toggle>(Key.MATCH_FRAME_RATE).checked)
        navigator(tvUi = false).show(Category.APPEARANCE)
        assertFalse(Key.MATCH_FRAME_RATE in keys())
    }

    @Test
    fun restartIsOfferedWhileASettingDiffersFromTheStart() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.APPEARANCE)
        assertFalse(screen.restartPending)
        assertTrue(row<Row.Choice>(Key.THEME).restart)
        assertFalse(row<Row.Toggle>(Key.KEEP_SCREEN_ON).restart)
        navigator.choose(Key.THEME, Config.Theme.BLACK.ordinal)
        assertEquals(Config.Theme.BLACK, store.theme)
        assertTrue(screen.restartPending)
        //it stays offered when the settings open again
        navigator.onHidden()
        navigator.show(Category.SEARCH)
        assertTrue(screen.restartPending)
        navigator.open(Category.APPEARANCE)
        navigator.choose(Key.THEME, Config.Theme.SYSTEM.ordinal)
        assertFalse(screen.restartPending)
        navigator.choose(Key.INTERFACE, Config.InterfaceMode.PHONE.ordinal)
        assertTrue(screen.restartPending)
        navigator.restart()
        assertEquals("restart", log.last())
    }

    @Test
    fun engineDebuggingAsksFirst() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.ADVANCED)
        confirmAnswer = false
        navigator.toggle(Key.ENGINE_DEBUG)
        assertEquals("confirm", log.last())
        assertFalse(store.webEngineDebug)
        assertFalse(row<Row.Toggle>(Key.ENGINE_DEBUG).checked)
        confirmAnswer = true
        navigator.toggle(Key.ENGINE_DEBUG)
        assertTrue(store.webEngineDebug)
        assertTrue(screen.restartPending)
        //turning it off does not ask
        log.clear()
        navigator.toggle(Key.ENGINE_DEBUG)
        assertFalse(store.webEngineDebug)
        assertTrue(log.isEmpty())
    }

    @Test
    fun clearCacheTellsWhenDone() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.ADVANCED)
        navigator.act(row<Row.Action>(Key.CLEAR_CACHE))
        assertTrue(store.cacheCleared)
        assertTrue(log.last().startsWith("message"))
    }

    @Test
    fun cursorSettingsKeepTheirRange() {
        val navigator = navigator(tvUi = true)
        navigator.show(Category.REMOTE)
        navigator.setSlider(Key.CURSOR_SPEED, 1000)
        assertEquals(Config.CURSOR_PHYSICS_PERCENT_MAX, store.cursorSpeed)
        navigator.setSlider(Key.CURSOR_ACCELERATION, 0)
        assertEquals(Config.CURSOR_PHYSICS_PERCENT_MIN, store.cursorAcceleration)
        assertEquals(Shortcut.entries.size, rows().count { it is Row.ShortcutKey })
        navigator.act(rows().first { it is Row.ShortcutKey })
        assertEquals("capture ${Shortcut.entries.first()}", log.last())
    }

    @Test
    fun updatesOnlyInTheGenericBuild() {
        store.builtInUpdates = false
        val navigator = navigator(tvUi = true)
        navigator.show(Category.ABOUT)
        assertTrue(store.checks.isEmpty())
        assertFalse(Key.AUTO_UPDATE in keys())
        assertTrue(Key.LINK_SOURCE in keys())
    }

    @Test
    fun newVersionCanBeInstalled() {
        store.updates = SettingsNavigator.Updates(listOf("release", "beta"), "9.9.9")
        val navigator = navigator(tvUi = true)
        navigator.show(Category.ABOUT)
        assertEquals(listOf(false), store.checks)
        assertEquals("Update", screen.categories.first { it.category == Category.ABOUT }.badge)
        navigator.act(row<Row.Action>(Key.UPDATE))
        assertEquals("update", log.last())
        assertTrue(row<Row.Choice>(Key.UPDATE_CHANNEL).segmented)
        navigator.choose(Key.UPDATE_CHANNEL, 1)
        assertEquals("beta", store.updateChannel)
        assertEquals(listOf(false, true), store.checks)
        //without automatic checks neither the version nor the channel is shown
        navigator.toggle(Key.AUTO_UPDATE)
        assertFalse(Key.UPDATE in keys())
        assertFalse(Key.UPDATE_CHANNEL in keys())
        assertNull(screen.categories.first { it.category == Category.ABOUT }.badge)
    }

    @Test
    fun linksOpenTheirPages() {
        val navigator = navigator(tvUi = false)
        navigator.show(Category.ABOUT)
        navigator.act(row<Row.Link>(Key.LINK_LICENSE))
        assertEquals("open ${SettingsNavigator.URL_LICENSE}", log.last())
    }

    @Test
    fun keyNamesReadLikeTheRemote() {
        assertEquals("Refresh", KeyBinding.nameOfConstant("KEYCODE_REFRESH"))
        assertEquals("Rewind", KeyBinding.nameOfConstant("KEYCODE_MEDIA_REWIND"))
        assertEquals("Page down", KeyBinding.nameOfConstant("KEYCODE_PAGE_DOWN"))
        assertEquals("10000", KeyBinding.nameOfConstant("10000"))
        assertEquals("Play/Pause", KeyBinding.keyName(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals("Fast forward", KeyBinding.keyName(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }
}
