package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.browser.adblock.FilterListCatalog
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.singleton.shortcuts.KeyBinding
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Date

/**
 * What the settings screens show and do, without their look: the TV screen over the window (the categories
 * on the left, the settings of one on the right) and the page of the phone (the categories, then the page of
 * one). A category is a list of sections of rows; a row that depends on another setting (the address of a
 * custom search engine, the filter list of ad blocking) is there only while it applies. Settings that apply
 * after a restart of the app are marked, and the screens offer the restart while one of them differs from the
 * value the process started with. Changes go to [Host.render].
 *
 * @param tvUi the TV interface: it has the settings of the remote and the virtual cursor
 */
class SettingsNavigator(
    private val context: Context,
    private val store: Store,
    private val scope: CoroutineScope,
    private val host: Host,
    private val tvUi: Boolean
) {
    enum class Category(@StringRes val title: Int, @DrawableRes val icon: Int) {
        SEARCH(R.string.settings_search_and_home, R.drawable.ic_search),
        SITES(R.string.settings_sites, R.drawable.ic_globe),
        APPEARANCE(R.string.settings_appearance, R.drawable.ic_appearance),
        REMOTE(R.string.settings_remote, R.drawable.ic_remote),
        ADVANCED(R.string.settings_advanced, R.drawable.ic_code),
        ABOUT(R.string.settings_about, R.drawable.ic_info)
    }

    /** The settings, and the rows that act */
    enum class Key {
        SEARCH_ENGINE, SEARCH_URL, HOME_PAGE, HOME_PAGE_URL, HOME_PAGE_LINKS,
        CHANNEL_BOOKMARKS, CHANNEL_BOOKMARKS_SOURCE, CHANNEL_TABS, CHANNEL_DOWNLOADS,
        SITE_VIEW, USER_AGENT, USER_AGENT_STRING, AUTOPLAY, AD_BLOCK, TRACKING_PROTECTION, FILTER_LIST, REGIONAL_LIST, CUSTOM_LIST, UPDATE_LISTS, VIDEO_HINT,
        INTERFACE, THEME, DARKENING, KEEP_SCREEN_ON, MATCH_FRAME_RATE,
        CURSOR_SPEED, CURSOR_ACCELERATION, JOYSTICK, SHORTCUT,
        CLEAR_CACHE, ENGINE_DEBUG, DOWNLOAD_FOLDER, PROXY, PROXY_TYPE, PROXY_HOST, PROXY_PORT, PROXY_USERNAME, PROXY_PASSWORD,
        INFO, UPDATE, AUTO_UPDATE, UPDATE_CHANNEL,
        LINK_SOURCE, LINK_SUPPORT, LINK_ICON, LINK_LICENSE, LINK_PRIVACY, LINK_PUBLIC_SUFFIX_LIST
    }

    /** The values that are read when the app starts: changing one needs a restart */
    data class StartupValues(
        val interfaceMode: Config.InterfaceMode,
        val theme: Config.Theme,
        val darkening: Boolean,
        val webEngineDebug: Boolean
    )

    /** A filter list as the settings show it; [filters] of the engine, [lastUpdate] 0 when never downloaded */
    data class FilterList(
        val id: String, val title: String, val enabled: Boolean, val regional: Boolean,
        val filters: Int, val lastUpdate: Long, val failed: Boolean
    )

    /** The lists of the catalog, the user's own list (when it has an address) and their updates */
    data class FilterLists(
        val lists: List<FilterList>, val customUrl: String, val custom: FilterList?, val updating: Boolean,
        val lastUpdate: Long, val skipped: Int
    )

    /** What the update check found; [newVersion] is null when the app is up to date */
    data class Updates(val channels: List<String>, val newVersion: String?)

    /**
     * Where the settings are kept. Setters apply them (the window flag of Keep screen on, the site view of
     * the tabs); checks of the engine and the build are made here, not in the navigator.
     */
    interface Store {
        val startupValues: StartupValues

        /** The address of the search engine, with [query] in place of the words */
        var searchEngineUrl: String
        val homePageMode: Config.HomePageMode
        val homePageUrl: String
        val homePageLinksMode: Config.HomePageLinksMode
        fun setHomePage(mode: Config.HomePageMode, customUrl: String?, linksMode: Config.HomePageLinksMode)

        /** The home screen of the TV shows channels of apps (Android 8+ with the TvProvider of Android TV) */
        val supportsHomeChannels: Boolean
        /** The setters of the channels create or delete them at once */
        var channelBookmarks: Boolean
        val channelBookmarksSource: Config.ChannelBookmarksSource
        /** The folder of [Config.ChannelBookmarksSource.FOLDER], [FavoritesRepository.ROOT] for the top level */
        val channelBookmarksFolder: Long
        fun setChannelBookmarks(source: Config.ChannelBookmarksSource, folder: Long)
        /** The folders of bookmarks in the order of their tree (the top level is not one) */
        suspend fun bookmarkFolders(): List<BookmarkFolder>
        var channelTabs: Boolean
        var channelDownloads: Boolean

        /** null follows the interface */
        var defaultDesktopView: Boolean?
        /** The User-Agent of all sites; null (Auto) follows the site view */
        var userAgent: String?
        var allowAutoplayMedia: Boolean
        var adBlockEnabled: Boolean
        /** The engine blocks trackers itself (GeckoView), apart from ad blocking */
        val supportsTrackingProtection: Boolean
        var trackingProtection: Boolean
        val filterLists: FilterLists
        /** Gives [filterLists] when they change (an update, a list turned on) */
        val filterListsChanges: Flow<FilterLists>
        fun setFilterListEnabled(id: String, enabled: Boolean)
        /** null: no regional list */
        fun setRegionalFilterList(id: String?)
        /** "" for none */
        fun setCustomFilterList(url: String)
        /** Downloads all enabled lists; false when one could not be downloaded */
        suspend fun updateFilterLists(): Boolean
        /** A hint about a found video that starts to play in the page (TV only) */
        var pageVideoHint: Boolean

        var interfaceMode: Config.InterfaceMode
        var theme: Config.Theme
        /** The engine can darken pages that have no dark look */
        val supportsDarkening: Boolean
        var darkening: Boolean
        var keepScreenOn: Boolean
        /** The built-in player switches the display to the frame rate of the video (TV only) */
        var matchFrameRate: Boolean

        var joystickNavigation: Boolean
        var cursorSpeed: Int
        var cursorAcceleration: Int
        fun bindingOf(shortcut: Shortcut): KeyBinding

        suspend fun clearCache()
        var webEngineDebug: Boolean
        /** The device has a picker of folders (the Storage Access Framework); many TVs have none */
        val downloadFolderPickerAvailable: Boolean
        /** The folder the user chose for downloads as it is shown, null for the Downloads directory */
        val downloadFolderName: String?
        /** Downloads go to the Downloads directory again */
        fun resetDownloadFolder()
        /** The engine can go through a proxy */
        val supportsProxy: Boolean
        /** The setters apply the proxy once its address is complete */
        var proxyEnabled: Boolean
        var proxyType: ProxySettings.Type
        var proxyHost: String
        /** 0 while not given */
        var proxyPort: Int
        var proxyUsername: String
        var proxyPassword: String
        /** The proxy the browser goes through: null while it is off or its address is not complete */
        val proxy: ProxySettings?

        val versionName: String
        /** The build (the app store flavor) */
        val buildName: String
        val engineVersion: String
        /** The app updates itself (the generic build) */
        val builtInUpdates: Boolean
        var autoCheckUpdates: Boolean
        var updateChannel: String
        /** null when the check failed */
        suspend fun checkUpdates(force: Boolean): Updates?
    }

    interface Host {
        fun render(screen: Screen)
        fun showMessage(@DrawableRes icon: Int, message: String)
        /** Asks the [message] with the [positive] button; [onResult] gets false when the user cancels */
        fun confirm(title: String?, message: String, positive: String, onResult: (Boolean) -> Unit)
        /** Lets the user press the key of the [shortcut]; [onDone] is called when the dialog closes */
        fun captureKey(shortcut: Shortcut, onDone: () -> Unit)
        /** Lets the user choose the folder of downloads in the picker of the system; [onDone] when it closes */
        fun pickDownloadFolder(onDone: () -> Unit)
        /**
         * Lets the user choose one of the [options] in a list that scrolls (there may be many); [checked] is the
         * chosen one, -1 for none. [onChosen] is not called when the user cancels.
         */
        fun pickFromList(title: String, options: List<String>, checked: Int, onChosen: (Int) -> Unit)
        /** Opens the page in a new tab (the screen closes) */
        fun open(url: String)
        /** Closes the screen and offers to install the new version */
        fun startUpdate()
        /** Restarts the app to apply the settings marked [Row.restart] */
        fun restart()
    }

    /** What the settings screens need from their shell */
    interface ShellCallback {
        val store: Store
        fun showMessage(@DrawableRes icon: Int, message: String)
        fun confirm(title: String?, message: String, positive: String, onResult: (Boolean) -> Unit)
        fun captureKey(shortcut: Shortcut, onDone: () -> Unit)
        fun pickDownloadFolder(onDone: () -> Unit)
        fun pickFromList(title: String, options: List<String>, checked: Int, onChosen: (Int) -> Unit)
        fun open(url: String)
        fun startUpdate()
        fun restart()
    }

    /** A folder of bookmarks the channel of the home screen can show; [path] is "A / B" */
    data class BookmarkFolder(val id: Long, val path: String)

    sealed class Row {
        abstract val key: Key
        abstract val title: String
        abstract val summary: String?
        /** The setting applies after a restart */
        open val restart: Boolean get() = false
        /** Unique in a page; the screens keep the view of a row by it */
        open val id: String get() = key.name

        /**
         * One of the [options]. [segmented] ones (2 or 3 short options) are shown side by side, the others
         * in a menu opened from the row, which shows the selected one.
         */
        data class Choice(
            override val key: Key, override val title: String, override val summary: String?,
            val options: List<String>, val selected: Int, val segmented: Boolean,
            override val restart: Boolean = false
        ) : Row()

        /** [item] tells apart the toggles of one [key] (the filter lists) */
        data class Toggle(
            override val key: Key, override val title: String, override val summary: String?,
            val checked: Boolean, override val restart: Boolean = false, val item: String? = null
        ) : Row() {
            override val id: String get() = if (item == null) key.name else key.name + "_" + item
        }

        /** A value from [min] to [max], shown as [label] */
        data class Slider(
            override val key: Key, override val title: String, override val summary: String?,
            val value: Int, val min: Int, val max: Int, val label: String
        ) : Row()

        /** A text to type; it is saved when the field loses the focus, on Done and when the screen closes */
        data class Field(
            override val key: Key, override val title: String, override val summary: String?,
            val text: String, val hint: String, val action: String? = null, val busy: Boolean = false,
            val input: Input = Input.TEXT
        ) : Row() {
            /** The keyboard of the field; a [PASSWORD] is shown hidden */
            enum class Input { TEXT, NUMBER, PASSWORD }
        }

        /** A button at the end of the row */
        data class Action(
            override val key: Key, override val title: String, override val summary: String?,
            val button: String
        ) : Row()

        /** The key of a [shortcut]; the row opens the key capture */
        data class ShortcutKey(
            val shortcut: Shortcut, override val title: String, val binding: KeyBinding
        ) : Row() {
            override val key: Key get() = Key.SHORTCUT
            override val summary: String? get() = null
            override val id: String get() = "${Key.SHORTCUT}_${shortcut.name}"
        }

        data class Link(override val key: Key, override val title: String, val url: String) : Row() {
            override val summary: String? get() = null
        }

        /** Lines of text (the version of the app) */
        data class Info(override val key: Key, override val title: String, override val summary: String?) : Row()
    }

    data class Section(val title: String?, val rows: List<Row>)

    data class CategoryItem(val category: Category, val title: String, val summary: String, val badge: String?)

    /**
     * @param page the category shown; null on the phone while it shows the list of categories
     * @param restartPending a setting changed that needs a restart
     */
    data class Screen(
        val categories: List<CategoryItem>,
        val page: Category?,
        val sections: List<Section>,
        val restartPending: Boolean
    )

    companion object {
        const val URL_SOURCE = "https://github.com/truefedex/tv-bro"
        const val URL_SUPPORT_AUTHOR = "https://donatello.to/truefedex"
        const val URL_WHY_ICON = "https://tv-bro-3546c.web.app/msg001.html"
        const val URL_LICENSE = "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/LICENSE.md"
        const val URL_PRIVACY_POLICY = "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/PRIVACY.md"
        /** The data of the ad blocker (MPL-2.0, which asks to tell where its source is) */
        const val URL_PUBLIC_SUFFIX_LIST = "https://publicsuffix.org/"

        /** The index of the custom search engine in the options of Search engine */
        val CUSTOM_SEARCH_ENGINE = Config.SearchEnginesURLs.size - 1

        /** The position of the Downloads directory in the options of the folder of downloads */
        const val DOWNLOAD_FOLDER_DEFAULT = 0

        /** Positions in the options of the bookmarks channel (the chosen folder is there only when one is) */
        const val CHANNEL_SOURCE_HOME_PAGE = 0
        const val CHANNEL_SOURCE_ALL = 1
        const val CHANNEL_SOURCE_FOLDER = 2

        /** Positions of the default site views */
        const val SITE_VIEW_AUTO = 0
        const val SITE_VIEW_DESKTOP_OPTION = 1
        const val SITE_VIEW_MOBILE_OPTION = 2

        fun siteViewOption(desktop: Boolean?) = when (desktop) {
            null -> SITE_VIEW_AUTO
            true -> SITE_VIEW_DESKTOP_OPTION
            false -> SITE_VIEW_MOBILE_OPTION
        }

        /**
         * The User-Agents of browsers the user can choose, by their names. Their versions are fixed: update them
         * now and then (a User-Agent stored by an older version shows as Custom then).
         */
        val USER_AGENT_PRESETS = listOf(
            "Chrome (Desktop)" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
            "Chrome (Mobile)" to "Mozilla/5.0 (Linux; Android 16; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36",
            "Firefox (Desktop)" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0",
            "Firefox (Mobile)" to "Mozilla/5.0 (Android 16; Mobile; rv:147.0) Gecko/147.0 Firefox/147.0",
            "Edge (Desktop)" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"
        )

        /** Positions of the User-Agent options: Auto, the presets, then Custom */
        const val USER_AGENT_AUTO = 0
        val USER_AGENT_CUSTOM = USER_AGENT_PRESETS.size + 1

        /** The option of the [userAgent]: Auto for none, a preset, or Custom */
        fun userAgentOption(userAgent: String?): Int {
            if (userAgent == null) return USER_AGENT_AUTO
            val preset = USER_AGENT_PRESETS.indexOfFirst { it.second == userAgent }
            return if (preset >= 0) preset + 1 else USER_AGENT_CUSTOM
        }

        /** The search engine of the [url]: one of the list, or the custom one */
        fun searchEngineOf(url: String): Int =
            Config.SearchEnginesURLs.indexOf(url).takeIf { it in 0 until CUSTOM_SEARCH_ENGINE } ?: CUSTOM_SEARCH_ENGINE
    }

    var page: Category? = null
        private set
    private var shown = false
    private var updates: Updates? = null
    private var filterListsUpdating = false
    /** Custom URL was chosen and no address is typed yet */
    private var customSearchChosen = false
    /** Custom was chosen for the User-Agent and none is typed yet */
    private var customUserAgentChosen = false
    private var jobs = emptyList<Job>()
    /** The folders of bookmarks, loaded when the screen shows (for the name of the one the channel shows) */
    private var bookmarkFolders: List<BookmarkFolder>? = null

    /** The categories of this interface */
    val categories: List<Category> get() = Category.entries.filter { it != Category.REMOTE || tvUi }

    /**
     * Opens the settings at the [category]: on TV the first one if not given, on the phone the list of
     * the categories
     */
    fun show(category: Category? = null) {
        shown = true
        page = category ?: if (tvUi) categories.first() else null
        customSearchChosen = false
        customUserAgentChosen = false
        jobs.forEach { it.cancel() }
        jobs = listOf(
            scope.launch {
                store.filterListsChanges.collect {
                    if (shown) render()
                }
            }
        )
        if (tvUi && store.supportsHomeChannels) {
            //the name of the folder the bookmarks channel shows
            jobs += scope.launch {
                bookmarkFolders = store.bookmarkFolders()
                if (shown) render()
            }
        }
        render()
        if (store.builtInUpdates) checkUpdates(force = false)
    }

    /** The screen closed */
    fun onHidden() {
        shown = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    fun open(category: Category) {
        if (page == category) return
        page = category
        customSearchChosen = false
        customUserAgentChosen = false
        render()
    }

    /** @return false when there is nothing to go back to (the screen closes) */
    fun back(): Boolean {
        if (tvUi || page == null) return false
        page = null
        customSearchChosen = false
        customUserAgentChosen = false
        render()
        return true
    }

    fun choose(key: Key, index: Int) {
        when (key) {
            Key.SEARCH_ENGINE -> if (index == CUSTOM_SEARCH_ENGINE) {
                customSearchChosen = searchEngineOf(store.searchEngineUrl) != CUSTOM_SEARCH_ENGINE
            } else {
                customSearchChosen = false
                store.searchEngineUrl = Config.SearchEnginesURLs[index]
            }
            Key.HOME_PAGE -> {
                val mode = Config.HomePageMode.entries[index]
                if (mode == store.homePageMode) return
                store.setHomePage(mode, customHomePageUrl().ifEmpty { null }?.takeIf { mode == Config.HomePageMode.CUSTOM },
                    store.homePageLinksMode)
            }
            Key.HOME_PAGE_LINKS -> store.setHomePage(store.homePageMode, customHomePageUrl(),
                Config.HomePageLinksMode.entries[index])
            Key.SITE_VIEW -> store.defaultDesktopView = when (index) {
                SITE_VIEW_DESKTOP_OPTION -> true
                SITE_VIEW_MOBILE_OPTION -> false
                else -> null
            }
            Key.USER_AGENT -> when (index) {
                USER_AGENT_CUSTOM -> customUserAgentChosen = userAgentOption(store.userAgent) != USER_AGENT_CUSTOM
                in 1..USER_AGENT_PRESETS.size -> {
                    customUserAgentChosen = false
                    store.userAgent = USER_AGENT_PRESETS[index - 1].second
                }
                else -> {
                    customUserAgentChosen = false
                    store.userAgent = null
                }
            }
            Key.INTERFACE -> store.interfaceMode = Config.InterfaceMode.entries[index]
            Key.THEME -> store.theme = Config.Theme.entries[index]
            Key.PROXY_TYPE -> {
                val type = ProxySettings.Type.entries.getOrNull(index) ?: return
                if (type == store.proxyType) return
                store.proxyType = type
            }
            Key.REGIONAL_LIST -> store.setRegionalFilterList(regionalLists().getOrNull(index - 1)?.id)
            Key.DOWNLOAD_FOLDER -> when {
                index == DOWNLOAD_FOLDER_DEFAULT ->
                    if (store.downloadFolderName != null) store.resetDownloadFolder() else return
                //Choose a folder… is the last option when there is a picker
                store.downloadFolderPickerAvailable && index == downloadFolderOptions().lastIndex -> {
                    host.pickDownloadFolder { render() }
                    return
                }
                else -> return
            }
            Key.CHANNEL_BOOKMARKS_SOURCE -> when (index) {
                CHANNEL_SOURCE_HOME_PAGE -> store.setChannelBookmarks(Config.ChannelBookmarksSource.HOME_PAGE, store.channelBookmarksFolder)
                CHANNEL_SOURCE_ALL -> store.setChannelBookmarks(Config.ChannelBookmarksSource.ALL, store.channelBookmarksFolder)
                //Choose a folder… is the last option, the chosen folder (if any) the one before it
                channelSourceOptions().lastIndex -> {
                    pickChannelFolder()
                    return
                }
                else -> return
            }
            Key.UPDATE_CHANNEL -> {
                val channel = updates?.channels?.getOrNull(index) ?: return
                if (channel == store.updateChannel) return
                store.updateChannel = channel
                checkUpdates(force = true)
            }
            else -> return
        }
        render()
    }

    /** A toggle row was clicked */
    fun toggle(row: Row.Toggle) {
        if (row.key == Key.FILTER_LIST && row.item != null) {
            store.setFilterListEnabled(row.item, !row.checked)
            return
        }
        toggle(row.key)
    }

    fun toggle(key: Key) {
        when (key) {
            Key.AUTOPLAY -> store.allowAutoplayMedia = !store.allowAutoplayMedia
            Key.CHANNEL_BOOKMARKS -> store.channelBookmarks = !store.channelBookmarks
            Key.CHANNEL_TABS -> store.channelTabs = !store.channelTabs
            Key.CHANNEL_DOWNLOADS -> store.channelDownloads = !store.channelDownloads
            Key.AD_BLOCK -> store.adBlockEnabled = !store.adBlockEnabled
            Key.TRACKING_PROTECTION -> store.trackingProtection = !store.trackingProtection
            Key.VIDEO_HINT -> store.pageVideoHint = !store.pageVideoHint
            Key.DARKENING -> store.darkening = !store.darkening
            Key.KEEP_SCREEN_ON -> store.keepScreenOn = !store.keepScreenOn
            Key.MATCH_FRAME_RATE -> store.matchFrameRate = !store.matchFrameRate
            Key.JOYSTICK -> store.joystickNavigation = !store.joystickNavigation
            Key.PROXY -> store.proxyEnabled = !store.proxyEnabled
            Key.AUTO_UPDATE -> {
                store.autoCheckUpdates = !store.autoCheckUpdates
                if (store.autoCheckUpdates && updates == null) checkUpdates(force = false)
            }
            Key.ENGINE_DEBUG -> if (store.webEngineDebug) {
                store.webEngineDebug = false
            } else {
                //it is shown as on until the user answers
                host.confirm(context.getString(R.string.warning), context.getString(R.string.web_engine_debug_warning_message),
                    context.getString(R.string.settings_turn_on)) { yes ->
                    if (yes) store.webEngineDebug = true
                    render()
                }
                return
            }
            else -> return
        }
        render()
    }

    fun setSlider(key: Key, value: Int) {
        val clamped = value.coerceIn(Config.CURSOR_PHYSICS_PERCENT_MIN, Config.CURSOR_PHYSICS_PERCENT_MAX)
        when (key) {
            Key.CURSOR_SPEED -> if (store.cursorSpeed != clamped) store.cursorSpeed = clamped else return
            Key.CURSOR_ACCELERATION -> if (store.cursorAcceleration != clamped) store.cursorAcceleration = clamped else return
            else -> return
        }
        render()
    }

    /** Saves the text of a field (unchanged texts are ignored) */
    fun setText(key: Key, text: String) {
        //a password is kept as it is typed
        val value = if (key == Key.PROXY_PASSWORD) text else text.trim()
        when (key) {
            Key.SEARCH_URL -> {
                if (value.isEmpty() || value == store.searchEngineUrl) return
                store.searchEngineUrl = value
                customSearchChosen = false
            }
            Key.USER_AGENT_STRING -> {
                //an empty field while Custom is chosen changes nothing; emptied, it gives Auto back
                if (value == (store.userAgent ?: "") || (customUserAgentChosen && value.isEmpty())) return
                store.userAgent = value.ifEmpty { null }
                customUserAgentChosen = false
            }
            Key.HOME_PAGE_URL -> {
                if (value == customHomePageUrl()) return
                store.setHomePage(Config.HomePageMode.CUSTOM, value.ifEmpty { null }, store.homePageLinksMode)
            }
            Key.CUSTOM_LIST -> {
                if (value == store.filterLists.customUrl) return
                store.setCustomFilterList(value)
            }
            Key.PROXY_HOST -> if (!setProxyAddress(value)) return
            Key.PROXY_PORT -> {
                val port = if (value.isEmpty()) 0 else value.toIntOrNull()?.coerceIn(0, 99999) ?: 0
                if (port == store.proxyPort) {
                    //a text that is not a port is replaced with the port
                    render()
                    return
                }
                store.proxyPort = port
            }
            Key.PROXY_USERNAME -> if (value != store.proxyUsername) store.proxyUsername = value else return
            Key.PROXY_PASSWORD -> if (value != store.proxyPassword) store.proxyPassword = value else return
            else -> return
        }
        render()
    }

    /**
     * Saves the address of the proxy; an address with a port (`host:8080`), a scheme (`socks5://host`) or a
     * path gives the port and the type too. @return false when nothing changed
     */
    private fun setProxyAddress(text: String): Boolean {
        var address = text
        val scheme = address.substringBefore("://", "").lowercase()
        if (address.contains("://")) address = address.substringAfter("://")
        address = address.substringBefore('/').substringAfterLast('@')
        var port: Int? = null
        val portStart = when {
            address.startsWith('[') -> address.indexOf("]:").takeIf { it >= 0 }?.plus(1)
            address.count { it == ':' } == 1 -> address.indexOf(':')
            else -> null
        }
        if (portStart != null) {
            port = address.substring(portStart + 1).toIntOrNull()
            address = address.substring(0, portStart)
        }
        val type = when (scheme) {
            "http", "https" -> ProxySettings.Type.HTTP
            "socks", "socks5", "socks5h" -> ProxySettings.Type.SOCKS5
            else -> null
        }
        var changed = false
        if (type != null && type != store.proxyType) {
            store.proxyType = type
            changed = true
        }
        if (port != null && port != store.proxyPort) {
            store.proxyPort = port
            changed = true
        }
        if (address != store.proxyHost) {
            store.proxyHost = address
            changed = true
        }
        //the field shows the address without the rest
        if (!changed && address != text) render()
        return changed
    }

    /** The button of an action row or a field, a link, or the key of a shortcut */
    fun act(row: Row) {
        when (row) {
            is Row.ShortcutKey -> host.captureKey(row.shortcut) { render() }
            is Row.Link -> host.open(row.url)
            else -> when (row.key) {
                Key.UPDATE_LISTS -> if (!filterListsUpdating && !store.filterLists.updating) {
                    filterListsUpdating = true
                    render()
                    scope.launch {
                        val ok = store.updateFilterLists()
                        filterListsUpdating = false
                        host.showMessage(if (ok) R.drawable.ic_check else R.drawable.ic_warning,
                            string(if (ok) R.string.filter_lists_updated else R.string.filter_lists_update_failed))
                        render()
                    }
                }
                Key.CLEAR_CACHE -> scope.launch {
                    store.clearCache()
                    host.showMessage(R.drawable.ic_check, context.getString(R.string.settings_web_cache_cleared))
                }
                Key.UPDATE -> if (updates?.newVersion != null) host.startUpdate()
                else -> {}
            }
        }
    }

    fun restart() = host.restart()

    private fun checkUpdates(force: Boolean) {
        scope.launch {
            val found = store.checkUpdates(force) ?: return@launch
            updates = found
            if (shown) render()
        }
    }

    private fun customHomePageUrl(): String =
        store.homePageUrl.takeIf { store.homePageMode == Config.HomePageMode.CUSTOM && it != Config.HOME_URL_ALIAS } ?: ""

    private val restartPending: Boolean get() {
        val startup = store.startupValues
        return store.interfaceMode != startup.interfaceMode || store.theme != startup.theme ||
                (store.supportsDarkening && store.darkening != startup.darkening) ||
                store.webEngineDebug != startup.webEngineDebug
    }

    private fun render() {
        if (!shown) return
        val page = page
        host.render(Screen(
            categories = categories.map { CategoryItem(it, context.getString(it.title), summaryOf(it), badgeOf(it)) },
            page = page,
            sections = if (page == null) emptyList() else sectionsOf(page).filter { it.rows.isNotEmpty() },
            restartPending = restartPending
        ))
    }

    private fun string(@StringRes id: Int) = context.getString(id)

    private fun badgeOf(category: Category): String? =
        if (category == Category.ABOUT && updates?.newVersion != null && store.autoCheckUpdates) string(R.string.update) else null

    private fun summaryOf(category: Category): String = when (category) {
        Category.SEARCH -> context.getString(R.string.settings_summary_two,
            searchEngineTitles()[searchEngineOf(store.searchEngineUrl)],
            context.resources.getStringArray(R.array.home_page_modes)[store.homePageMode.ordinal])
        Category.SITES -> context.getString(R.string.settings_summary_two,
            string(if (store.adBlockEnabled) R.string.settings_summary_ads_blocked else R.string.settings_summary_ads_shown),
            context.getString(R.string.settings_summary_site_view, siteViewOptions()[siteViewOption(store.defaultDesktopView)]))
        Category.APPEARANCE -> context.getString(R.string.settings_summary_two,
            context.getString(R.string.settings_summary_interface, interfaceOptions()[store.interfaceMode.ordinal]),
            context.getString(R.string.settings_summary_theme, themeOptions()[store.theme.ordinal]))
        Category.REMOTE -> string(R.string.settings_summary_remote)
        Category.ADVANCED -> store.proxy?.takeIf { store.supportsProxy }
            ?.let { context.getString(R.string.settings_summary_proxy_s, proxyAddress(it)) }
            ?: string(R.string.settings_summary_advanced)
        Category.ABOUT -> context.getString(R.string.settings_version_s, store.versionName)
    }

    private fun searchEngineTitles() =
        Config.SearchEnginesTitles.dropLast(1) + string(R.string.settings_custom_url)

    private fun siteViewOptions() = listOf(string(R.string.settings_auto), string(R.string.settings_desktop),
        string(R.string.settings_mobile))

    private fun userAgentOptions() = listOf(string(R.string.settings_auto)) + USER_AGENT_PRESETS.map { it.first } +
        string(R.string.settings_user_agent_custom)

    /** The User-Agent (the field of a custom one only while Custom is chosen) */
    private fun userAgentRows(): List<Row> {
        val userAgent = store.userAgent
        val option = if (customUserAgentChosen) USER_AGENT_CUSTOM else userAgentOption(userAgent)
        val rows = arrayListOf<Row>(Row.Choice(Key.USER_AGENT, string(R.string.settings_user_agent),
            string(R.string.settings_user_agent_summary), userAgentOptions(), option, segmented = false))
        if (option == USER_AGENT_CUSTOM) {
            rows += Row.Field(Key.USER_AGENT_STRING, string(R.string.settings_user_agent_string), null,
                if (customUserAgentChosen) "" else userAgent ?: "", "Mozilla/5.0 (…)")
        }
        return rows
    }

    private fun interfaceOptions() = listOf(string(R.string.settings_auto), string(R.string.settings_interface_tv),
        string(R.string.settings_interface_phone))

    private fun themeOptions() = context.resources.getStringArray(R.array.themes).toList()

    private fun sectionsOf(category: Category): List<Section> = when (category) {
        Category.SEARCH -> searchSections()
        Category.SITES -> sitesSections()
        Category.APPEARANCE -> appearanceSections()
        Category.REMOTE -> remoteSections()
        Category.ADVANCED -> advancedSections()
        Category.ABOUT -> aboutSections()
    }

    private fun searchSections(): List<Section> {
        val engine = if (customSearchChosen) CUSTOM_SEARCH_ENGINE else searchEngineOf(store.searchEngineUrl)
        val search = arrayListOf<Row>(Row.Choice(Key.SEARCH_ENGINE, string(R.string.settings_search_engine),
            string(R.string.settings_search_engine_summary), searchEngineTitles(), engine, segmented = false))
        if (engine == CUSTOM_SEARCH_ENGINE) {
            search += Row.Field(Key.SEARCH_URL, string(R.string.settings_search_url), null,
                if (customSearchChosen) "" else store.searchEngineUrl, string(R.string.settings_search_url_hint))
        }
        val mode = store.homePageMode
        val home = arrayListOf<Row>(Row.Choice(Key.HOME_PAGE, string(R.string.home_page),
            string(R.string.settings_home_page_summary),
            context.resources.getStringArray(R.array.home_page_modes).toList(), mode.ordinal, segmented = false))
        when (mode) {
            Config.HomePageMode.HOME_PAGE -> home += Row.Choice(Key.HOME_PAGE_LINKS,
                string(R.string.settings_home_page_links), string(R.string.settings_home_page_links_summary),
                context.resources.getStringArray(R.array.home_page_links_modes).toList(),
                store.homePageLinksMode.ordinal, segmented = false)
            Config.HomePageMode.CUSTOM -> home += Row.Field(Key.HOME_PAGE_URL, string(R.string.settings_home_page_url),
                null, customHomePageUrl(), string(R.string.settings_home_page_url_hint))
            else -> {}
        }
        val sections = arrayListOf(Section(string(R.string.settings_group_search), search),
            Section(string(R.string.settings_group_home_page), home))
        if (tvUi && store.supportsHomeChannels) {
            sections += Section(string(R.string.settings_group_home_screen), channelRows())
        }
        return sections
    }

    /** The channels of the Android TV home screen */
    private fun channelRows(): List<Row> {
        val rows = arrayListOf<Row>(Row.Toggle(Key.CHANNEL_BOOKMARKS, string(R.string.settings_channel_bookmarks),
            string(R.string.settings_channel_bookmarks_summary), store.channelBookmarks))
        if (store.channelBookmarks) {
            val selected = when {
                chosenChannelFolderName() != null -> CHANNEL_SOURCE_FOLDER
                store.channelBookmarksSource == Config.ChannelBookmarksSource.ALL -> CHANNEL_SOURCE_ALL
                else -> CHANNEL_SOURCE_HOME_PAGE
            }
            rows += Row.Choice(Key.CHANNEL_BOOKMARKS_SOURCE, string(R.string.settings_channel_bookmarks_source),
                if (selected == CHANNEL_SOURCE_FOLDER) string(R.string.settings_channel_folder_summary) else null,
                channelSourceOptions(), selected, segmented = false)
        }
        rows += Row.Toggle(Key.CHANNEL_TABS, string(R.string.settings_channel_tabs),
            string(R.string.settings_channel_tabs_summary), store.channelTabs)
        rows += Row.Toggle(Key.CHANNEL_DOWNLOADS, string(R.string.settings_channel_downloads),
            string(R.string.settings_channel_downloads_summary), store.channelDownloads)
        return rows
    }

    /** Home page bookmarks, all bookmarks, the chosen folder (if one is) and the choice of a folder */
    private fun channelSourceOptions(): List<String> {
        val options = arrayListOf(string(R.string.settings_channel_source_home_page), string(R.string.settings_channel_source_all))
        chosenChannelFolderName()?.let { options += it }
        options += string(R.string.settings_channel_source_choose_folder)
        return options
    }

    /**
     * The name of the folder the bookmarks channel shows; null when it shows no folder, or the folder is gone
     * (the channel shows the home page bookmarks then)
     */
    private fun chosenChannelFolderName(): String? {
        if (store.channelBookmarksSource != Config.ChannelBookmarksSource.FOLDER) return null
        val folder = store.channelBookmarksFolder
        if (folder == FavoritesRepository.ROOT) return string(R.string.settings_channel_folder_top_level)
        val folders = bookmarkFolders ?: return "…"
        return folders.find { it.id == folder }?.path
    }

    private fun pickChannelFolder() {
        scope.launch {
            val folders = store.bookmarkFolders().also { bookmarkFolders = it }
            val choices = listOf(BookmarkFolder(FavoritesRepository.ROOT, string(R.string.settings_channel_folder_top_level))) + folders
            val checked = if (store.channelBookmarksSource == Config.ChannelBookmarksSource.FOLDER)
                choices.indexOfFirst { it.id == store.channelBookmarksFolder } else -1
            host.pickFromList(string(R.string.settings_channel_bookmarks_source), choices.map { it.path }, checked) { index ->
                store.setChannelBookmarks(Config.ChannelBookmarksSource.FOLDER, choices[index].id)
                render()
            }
        }
    }

    private fun sitesSections(): List<Section> {
        val siteView = listOf(
            Row.Choice(Key.SITE_VIEW, string(R.string.default_site_view),
                string(if (tvUi) R.string.settings_site_view_summary_tv else R.string.settings_site_view_summary_phone),
                siteViewOptions(), siteViewOption(store.defaultDesktopView), segmented = true)
        ) + userAgentRows() + Row.Toggle(Key.AUTOPLAY, string(R.string.settings_autoplay),
            string(R.string.settings_autoplay_summary), store.allowAutoplayMedia)
        val adBlock = arrayListOf<Row>(Row.Toggle(Key.AD_BLOCK, string(R.string.settings_block_ads),
            string(if (tvUi) R.string.settings_block_ads_summary_tv else R.string.settings_block_ads_summary_phone),
            store.adBlockEnabled))
        if (store.supportsTrackingProtection) {
            adBlock += Row.Toggle(Key.TRACKING_PROTECTION, string(R.string.settings_tracking_protection),
                string(R.string.settings_tracking_protection_summary), store.trackingProtection)
        }
        val sections = arrayListOf(Section(string(R.string.settings_group_site_view), siteView),
            Section(string(R.string.settings_group_ad_blocking), adBlock))
        if (store.adBlockEnabled) sections += Section(string(R.string.settings_group_filter_lists), filterListRows())
        //the built-in player and its hint are on TV only
        if (tvUi) {
            sections += Section(string(R.string.settings_group_videos), listOf(Row.Toggle(Key.VIDEO_HINT,
                string(R.string.settings_video_hint), string(R.string.settings_video_hint_summary), store.pageVideoHint)))
        }
        return sections
    }

    private fun regionalLists() = store.filterLists.lists.filter { it.regional }

    private fun filterListRows(): List<Row> {
        val state = store.filterLists
        val rows = ArrayList<Row>()
        for ((id, what) in listOf(FilterListCatalog.EASYLIST to R.string.settings_filter_list_ads,
                FilterListCatalog.EASYPRIVACY to R.string.settings_filter_list_trackers)) {
            val list = state.lists.firstOrNull { it.id == id } ?: continue
            rows += Row.Toggle(Key.FILTER_LIST, list.title, listSummary(string(what), list), list.enabled, item = id)
        }
        val regional = regionalLists()
        val selected = regional.indexOfFirst { it.enabled }
        rows += Row.Choice(Key.REGIONAL_LIST, string(R.string.settings_regional_filter_list),
            regional.getOrNull(selected)?.let { listSummary(string(R.string.settings_regional_filter_list_summary), it) }
                ?: string(R.string.settings_regional_filter_list_summary),
            listOf(string(R.string.settings_no_filter_list)) + regional.map { it.title }, selected + 1, segmented = false)
        rows += Row.Field(Key.CUSTOM_LIST, string(R.string.settings_custom_filter_list),
            state.custom?.let { listState(it) }, state.customUrl, string(R.string.settings_custom_filter_list_hint))
        val updated = when {
            state.updating || filterListsUpdating -> string(R.string.settings_filter_list_updating)
            state.lastUpdate == 0L -> string(R.string.settings_filter_list_never_updated)
            else -> context.getString(R.string.settings_filter_list_updated_s, formatTime(state.lastUpdate))
        }
        rows += Row.Action(Key.UPDATE_LISTS, string(R.string.settings_update_filter_lists),
            if (state.skipped > 0) context.getString(R.string.settings_filter_lists_skipped, updated, state.skipped) else updated,
            string(R.string.update))
        return rows
    }

    private fun listSummary(what: String, list: FilterList): String =
        if (list.enabled) context.getString(R.string.settings_filter_list_summary, what, listState(list)) else what

    /** The filters of the list, or why there are none */
    private fun listState(list: FilterList): String = when {
        list.failed -> string(R.string.settings_filter_list_failed)
        list.lastUpdate == 0L -> string(R.string.settings_filter_list_not_loaded)
        else -> context.resources.getQuantityString(R.plurals.settings_filter_count, list.filters, list.filters)
    }

    private fun appearanceSections(): List<Section> {
        val ui = arrayListOf<Row>(
            Row.Choice(Key.INTERFACE, string(R.string.interface_mode), string(R.string.settings_interface_summary),
                interfaceOptions(), store.interfaceMode.ordinal, segmented = true, restart = true),
            Row.Choice(Key.THEME, string(R.string.theme), string(R.string.settings_theme_summary),
                themeOptions(), store.theme.ordinal, segmented = true, restart = true)
        )
        if (store.supportsDarkening) {
            ui += Row.Toggle(Key.DARKENING, string(R.string.settings_darken_pages),
                string(R.string.settings_darken_pages_summary), store.darkening, restart = true)
        }
        val screen = arrayListOf<Row>(Row.Toggle(Key.KEEP_SCREEN_ON, string(R.string.toggle_keep_screen_on),
            string(if (tvUi) R.string.settings_keep_screen_on_summary_tv else R.string.settings_keep_screen_on_summary_phone),
            store.keepScreenOn))
        //the built-in player is on TV only
        if (tvUi) {
            screen += Row.Toggle(Key.MATCH_FRAME_RATE, string(R.string.settings_match_frame_rate),
                string(R.string.settings_match_frame_rate_summary), store.matchFrameRate)
        }
        return listOf(Section(string(R.string.settings_group_interface), ui),
            Section(string(R.string.settings_group_screen), screen))
    }

    private fun remoteSections(): List<Section> {
        val min = Config.CURSOR_PHYSICS_PERCENT_MIN
        val max = Config.CURSOR_PHYSICS_PERCENT_MAX
        val cursor = listOf(
            Row.Slider(Key.CURSOR_SPEED, string(R.string.settings_cursor_speed), string(R.string.settings_cursor_speed_summary),
                store.cursorSpeed, min, max, context.getString(R.string.settings_percent, store.cursorSpeed)),
            Row.Slider(Key.CURSOR_ACCELERATION, string(R.string.settings_cursor_acceleration),
                string(R.string.settings_cursor_acceleration_summary), store.cursorAcceleration, min, max,
                context.getString(R.string.settings_percent, store.cursorAcceleration)),
            Row.Toggle(Key.JOYSTICK, string(R.string.settings_joystick), string(R.string.settings_joystick_summary),
                store.joystickNavigation)
        )
        val keys = Shortcut.entries.map { Row.ShortcutKey(it, string(it.titleResId), store.bindingOf(it)) }
        return listOf(Section(string(R.string.settings_group_cursor), cursor),
            Section(string(R.string.settings_group_keys), keys))
    }

    private fun advancedSections(): List<Section> {
        val sections = arrayListOf(Section(null, listOf(
            Row.Action(Key.CLEAR_CACHE, string(R.string.settings_web_cache), string(R.string.settings_web_cache_summary),
                string(R.string.clear)),
            Row.Toggle(Key.ENGINE_DEBUG, string(R.string.settings_engine_debug), string(R.string.settings_engine_debug_summary),
                store.webEngineDebug, restart = true)
        )))
        //the row stays while a folder is chosen, so that it can be reset
        if (store.downloadFolderPickerAvailable || store.downloadFolderName != null) {
            val options = downloadFolderOptions()
            sections += Section(string(R.string.settings_group_downloads), listOf(Row.Choice(Key.DOWNLOAD_FOLDER,
                string(R.string.settings_download_folder), string(R.string.settings_download_folder_summary), options,
                if (store.downloadFolderName != null) DOWNLOAD_FOLDER_DEFAULT + 1 else DOWNLOAD_FOLDER_DEFAULT,
                segmented = false)))
        }
        if (store.supportsProxy) sections += Section(string(R.string.settings_group_proxy), proxyRows())
        return sections
    }

    /** The Downloads directory, the chosen folder (if there is one) and the choice of another one */
    private fun downloadFolderOptions(): List<String> {
        val options = arrayListOf(string(R.string.settings_download_folder_default))
        store.downloadFolderName?.let { options += it }
        if (store.downloadFolderPickerAvailable) options += string(R.string.settings_download_folder_choose)
        return options
    }

    private fun proxyTypeOptions() = listOf(string(R.string.settings_proxy_http), string(R.string.settings_proxy_socks5))

    /** "HTTP host:8080" */
    private fun proxyAddress(proxy: ProxySettings): String =
        "${proxyTypeOptions()[proxy.type.ordinal]} ${proxy.host}:${proxy.port}"

    private fun proxyRows(): List<Row> {
        val enabled = store.proxyEnabled
        val proxy = store.proxy
        val summary = when {
            !enabled -> string(R.string.settings_proxy_summary)
            proxy == null -> string(R.string.settings_proxy_incomplete)
            else -> context.getString(R.string.settings_proxy_in_use_s, proxyAddress(proxy))
        }
        val rows = arrayListOf<Row>(Row.Toggle(Key.PROXY, string(R.string.settings_proxy), summary, enabled))
        if (!enabled) return rows
        val type = store.proxyType
        rows += Row.Choice(Key.PROXY_TYPE, string(R.string.settings_proxy_type),
            string(if (type == ProxySettings.Type.HTTP) R.string.settings_proxy_type_http_summary
                else R.string.settings_proxy_type_socks5_summary),
            proxyTypeOptions(), type.ordinal, segmented = true)
        val host = store.proxyHost
        rows += Row.Field(Key.PROXY_HOST, string(R.string.settings_proxy_host),
            string(R.string.settings_proxy_bad_host).takeIf { host.isNotEmpty() && !ProxySettings.isValidHost(host) },
            host, string(R.string.settings_proxy_host_hint))
        val port = store.proxyPort
        rows += Row.Field(Key.PROXY_PORT, string(R.string.settings_proxy_port),
            context.getString(R.string.settings_proxy_bad_port, ProxySettings.MIN_PORT, ProxySettings.MAX_PORT)
                .takeIf { port != 0 && !ProxySettings.isValidPort(port) },
            if (port == 0) "" else port.toString(),
            if (type == ProxySettings.Type.HTTP) "8080" else "1080", input = Row.Field.Input.NUMBER)
        //WebView can not log in to a SOCKS proxy
        if (type == ProxySettings.Type.HTTP) {
            rows += Row.Field(Key.PROXY_USERNAME, string(R.string.settings_proxy_username),
                string(R.string.settings_proxy_login_summary), store.proxyUsername, "")
            rows += Row.Field(Key.PROXY_PASSWORD, string(R.string.settings_proxy_password), null, store.proxyPassword, "",
                input = Row.Field.Input.PASSWORD)
        }
        return rows
    }

    private fun aboutSections(): List<Section> {
        val sections = arrayListOf(Section(null, listOf(Row.Info(Key.INFO, string(R.string.web_browser_optimized_for_tvs),
            context.getString(R.string.settings_version_line, store.versionName, store.buildName, store.engineVersion)))))
        if (store.builtInUpdates) {
            val rows = ArrayList<Row>()
            val updates = updates
            if (store.autoCheckUpdates && updates?.newVersion != null) {
                rows += Row.Action(Key.UPDATE, context.getString(R.string.settings_new_version_s, updates.newVersion),
                    context.getString(R.string.settings_channel_s, store.updateChannel), string(R.string.update))
            }
            rows += Row.Toggle(Key.AUTO_UPDATE, string(R.string.auto_check_for_updates), null, store.autoCheckUpdates)
            val channels = updates?.channels.orEmpty()
            if (store.autoCheckUpdates && channels.isNotEmpty()) {
                rows += Row.Choice(Key.UPDATE_CHANNEL, string(R.string.settings_update_channel),
                    string(R.string.settings_update_channel_summary), channels,
                    channels.indexOf(store.updateChannel).coerceAtLeast(0), segmented = channels.size <= 3)
            }
            sections += Section(string(R.string.settings_group_updates), rows)
        }
        sections += Section(string(R.string.settings_group_links), listOf(
            Row.Link(Key.LINK_SOURCE, string(R.string.settings_source_code), URL_SOURCE),
            Row.Link(Key.LINK_SUPPORT, string(R.string.settings_support_author), URL_SUPPORT_AUTHOR),
            Row.Link(Key.LINK_ICON, string(R.string.why_icon_changed), URL_WHY_ICON),
            Row.Link(Key.LINK_LICENSE, string(R.string.settings_license), URL_LICENSE),
            Row.Link(Key.LINK_PRIVACY, string(R.string.settings_privacy_policy), URL_PRIVACY_POLICY),
            Row.Link(Key.LINK_PUBLIC_SUFFIX_LIST, string(R.string.settings_public_suffix_list), URL_PUBLIC_SUFFIX_LIST)
        ))
        return sections
    }

    private fun formatTime(millis: Long): String {
        val date = Date(millis)
        return "${DateFormat.getTimeFormat(context).format(date)}, ${DateFormat.getMediumDateFormat(context).format(date)}"
    }
}
