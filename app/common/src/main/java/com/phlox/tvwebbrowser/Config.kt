package com.phlox.tvwebbrowser

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.observable.ObservableValue

/**
 * The settings of the app. [credentialsPrefs] keeps the secrets (the login of the proxy): its file is left out
 * of backups.
 */
class Config(val prefs: SharedPreferences, private val credentialsPrefs: SharedPreferences = prefs) {
    companion object {
        const val SEARCH_ENGINE_URL_PREF_KEY = "search_engine_url"
        const val SEARCH_ENGINE_AS_HOME_PAGE_KEY = "search_engine_as_home_page"
        const val HOME_PAGE_KEY = "home_page"
        const val DEFAULT_SITE_VIEW_KEY = "default_desktop_view"
        const val THEME_KEY = "theme"
        const val INTERFACE_MODE_KEY = "interface_mode"
        const val LAST_UPDATE_USER_NOTIFICATION_TIME_KEY = "last_update_notif"
        const val AUTO_CHECK_UPDATES_KEY = "auto_check_updates"
        const val UPDATE_CHANNEL_KEY = "update_channel"
        const val HOME_URL_ALIAS = "about:home"
        const val KEEP_SCREEN_ON_KEY = "keep_screen_on"
        const val MATCH_FRAME_RATE_KEY = "match_frame_rate"
        const val PAGE_VIDEO_HINT_KEY = "page_video_hint"
        const val BUILT_IN_PLAYER_OPENS_KEY = "built_in_player_opens"
        /** When true, analog stick / hat axes from generic motion events are not translated to DPAD keys. */
        const val DISABLE_MOTION_AXES_DPAD_NAVIGATION_KEY = "disable_motion_axes_dpad_navigation"
        /** Percent of built-in default (100 = default). Range [CURSOR_PHYSICS_PERCENT_MIN], [CURSOR_PHYSICS_PERCENT_MAX]. */
        const val CURSOR_MAX_SPEED_PERCENT_KEY = "cursor_max_speed_percent"
        const val CURSOR_ACCELERATION_PERCENT_KEY = "cursor_acceleration_percent"
        const val CURSOR_PHYSICS_PERCENT_MIN = 25
        const val CURSOR_PHYSICS_PERCENT_MAX = 200
        const val INCOGNITO_MODE_KEY = "incognito_mode"
        const val INCOGNITO_MODE_HINT_SUPPRESS_KEY = "incognito_mode_hint_suppress"
        const val DIRECT_NAVIGATION_MODE_HINT_SUPPRESS_KEY = "direct_navigation_mode_hint_suppress"
        const val HOME_PAGE_MODE = "home_page_mode"
        const val HOME_PAGE_SUGGESTIONS_MODE = "home_page_suggestions_mode"
        const val ALLOW_AUTOPLAY_MEDIA = "allow_autoplay_media"
        const val WEBVIEW_USE_ALGORITHMIC_DARKENING_WITH_DARK_UI_MODE_KEY =
            "webview_use_algorithmic_darkening_with_dark_ui_mode"
        const val WEB_ENGINE_DEBUG_KEY = "web_engine_debug"
        const val INITIAL_BOOKMARKS_SUGGESTIONS_LOADED = "initial_bookmarks_suggestions_loaded"
        const val ADBLOCK_ENABLED_PREF_KEY = "adblock_enabled"
        const val TRACKING_PROTECTION_KEY = "tracking_protection"
        const val APP_WEB_EXTENSION_VERSION_KEY = "app_web_extension_version"
        const val APP_VERSION_CODE_MARK_KEY = "app_version_code_mark"
        const val PROXY_ENABLED_KEY = "proxy_enabled"
        const val PROXY_TYPE_KEY = "proxy_type"
        const val PROXY_HOST_KEY = "proxy_host"
        const val PROXY_PORT_KEY = "proxy_port"
        const val PROXY_USERNAME_KEY = "proxy_username"
        const val PROXY_PASSWORD_KEY = "proxy_password"
        const val DOWNLOAD_FOLDER_URI_KEY = "download_folder_uri"
        const val DOWNLOAD_FOLDER_NAME_KEY = "download_folder_name"
        const val CHANNEL_BOOKMARKS_KEY = "channel_bookmarks"
        const val CHANNEL_BOOKMARKS_SOURCE_KEY = "channel_bookmarks_source"
        const val CHANNEL_BOOKMARKS_FOLDER_KEY = "channel_bookmarks_folder"
        const val CHANNEL_TABS_KEY = "channel_tabs"
        const val CHANNEL_DOWNLOADS_KEY = "channel_downloads"
        /** The prefs of [credentialsPrefs] (the file `credentials.xml`), left out of backups (`backup_rules.xml`) */
        const val CREDENTIALS_PREFS_NAME = "credentials"

        val SearchEnginesTitles = arrayOf("Google", "Bing", "Yahoo!", "DuckDuckGo", "Yandex", "Startpage", "Custom")
        val SearchEnginesNames = arrayOf("google", "bing", "yahoo", "ddg", "yandex", "startpage", "custom")
        val SearchEnginesURLs = listOf("https://www.google.com/search?q=[query]", "https://www.bing.com/search?q=[query]",
            "https://search.yahoo.com/search?p=[query]", "https://duckduckgo.com/?q=[query]",
            "https://yandex.com/search/?text=[query]", "https://www.startpage.com/sp/search?query=[query]", "")
        const val HOME_PAGE_URL = "https://tvbro.phlox.dev/appcontent/home/"
        //const val HOME_PAGE_URL = "http://10.0.2.2:5000/appcontent/home/"
    }

    enum class Theme {
        SYSTEM, WHITE, BLACK;
    }

    /** The look of the browser screen: picked from the device (Auto) or chosen */
    enum class InterfaceMode {
        AUTO, TV, PHONE
    }

    enum class HomePageMode {
        HOME_PAGE, SEARCH_ENGINE, CUSTOM, BLANK
    }

    enum class HomePageLinksMode {
        BOOKMARKS, LATEST_HISTORY, MOST_VISITED
    }

    /** The bookmarks the channel of the TV home screen shows */
    enum class ChannelBookmarksSource {
        /** The bookmarks of the built-in home page */
        HOME_PAGE,
        /** The bookmarks of [channelBookmarksFolder], without its folders */
        FOLDER,
        /** All bookmarks of all folders and of the home page */
        ALL
    }

    var incognitoMode: Boolean
        get() = prefs.getBoolean(INCOGNITO_MODE_KEY, false)
        @SuppressLint("ApplySharedPref")
        set(value) {
            prefs.edit().putBoolean(INCOGNITO_MODE_KEY, value).commit()
        }

    var incognitoModeHintSuppress: Boolean
        get() = prefs.getBoolean(INCOGNITO_MODE_HINT_SUPPRESS_KEY, false)
        set(value) {
            prefs.edit().putBoolean(INCOGNITO_MODE_HINT_SUPPRESS_KEY, value).apply()
        }

    var directNavigationModeHintSuppress: Boolean
        get() = prefs.getBoolean(DIRECT_NAVIGATION_MODE_HINT_SUPPRESS_KEY, false)
        set(value) {
            prefs.edit().putBoolean(DIRECT_NAVIGATION_MODE_HINT_SUPPRESS_KEY, value).apply()
        }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON_KEY, false)
        set(value) {
            prefs.edit().putBoolean(KEEP_SCREEN_ON_KEY, value).apply()
        }

    /** The built-in player switches the display to the frame rate of the video (the screen blanks for a moment) */
    var matchFrameRate: Boolean
        get() = prefs.getBoolean(MATCH_FRAME_RATE_KEY, false)
        set(value) {
            prefs.edit().putBoolean(MATCH_FRAME_RATE_KEY, value).apply()
        }

    /** The TV tells about a found video of the page that starts to play in the page (the hint plate) */
    var pageVideoHint: Boolean
        get() = prefs.getBoolean(PAGE_VIDEO_HINT_KEY, true)
        set(value) {
            prefs.edit().putBoolean(PAGE_VIDEO_HINT_KEY, value).apply()
        }

    /** How many times the user opened a video in the built-in player (the hint plate stops after a few) */
    var builtInPlayerOpens: Int
        get() = prefs.getInt(BUILT_IN_PLAYER_OPENS_KEY, 0)
        set(value) {
            prefs.edit().putInt(BUILT_IN_PLAYER_OPENS_KEY, value).apply()
        }

    var disableMotionAxesDpadNavigation: Boolean
        get() = prefs.getBoolean(DISABLE_MOTION_AXES_DPAD_NAVIGATION_KEY, false)
        set(value) {
            prefs.edit().putBoolean(DISABLE_MOTION_AXES_DPAD_NAVIGATION_KEY, value).apply()
        }

    /** Virtual cursor max speed as a percent of the default (screen-width–based) cap. */
    var cursorMaxSpeedPercent: Int
        get() = prefs.getInt(CURSOR_MAX_SPEED_PERCENT_KEY, 100)
            .coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
        set(value) {
            prefs.edit().putInt(
                CURSOR_MAX_SPEED_PERCENT_KEY,
                value.coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
            ).apply()
        }

    /** Virtual cursor acceleration as a percent of the default ramp-up per frame. */
    var cursorAccelerationPercent: Int
        get() = prefs.getInt(CURSOR_ACCELERATION_PERCENT_KEY, 100)
            .coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
        set(value) {
            prefs.edit().putInt(
                CURSOR_ACCELERATION_PERCENT_KEY,
                value.coerceIn(CURSOR_PHYSICS_PERCENT_MIN, CURSOR_PHYSICS_PERCENT_MAX)
            ).apply()
        }

    var theme = object : ObservableValue<Theme>(Theme.SYSTEM) {
        override var value: Theme = Theme.entries[prefs.getInt(THEME_KEY, 0)]
            set(value) {
                prefs.edit().putInt(THEME_KEY, value.ordinal).apply()
                field = value
                notifyObservers()
            }
    }

    var interfaceMode: InterfaceMode
        get() = InterfaceMode.entries.getOrElse(prefs.getInt(INTERFACE_MODE_KEY, 0)) { InterfaceMode.AUTO }
        set(value) {
            prefs.edit().putInt(INTERFACE_MODE_KEY, value.ordinal).apply()
        }

    var homePageMode: HomePageMode
        get() = prefs.getInt(HOME_PAGE_MODE, 0)
            .let {
                //ignore value if search engine as home page is set
                if (prefs.getBoolean(SEARCH_ENGINE_AS_HOME_PAGE_KEY, false)) {
                    prefs.edit()
                        .remove(SEARCH_ENGINE_AS_HOME_PAGE_KEY)
                        .putInt(HOME_PAGE_MODE, HomePageMode.SEARCH_ENGINE.ordinal)
                        .apply()
                    HomePageMode.SEARCH_ENGINE.ordinal
                } else it
            }
            .let { if (it < 0 || it >= HomePageMode.values().size) 0 else it }
            .let { HomePageMode.values()[it] }
        set(value) {
            prefs.edit().putInt(HOME_PAGE_MODE, value.ordinal).apply()
        }

    var homePageLinksMode: HomePageLinksMode
        get() = prefs.getInt(HOME_PAGE_SUGGESTIONS_MODE, 0)
            .let { if (it < 0 || it >= HomePageLinksMode.values().size) 0 else it }
            .let { HomePageLinksMode.values()[it] }
        set(value) {
            prefs.edit().putInt(HOME_PAGE_SUGGESTIONS_MODE, value.ordinal).apply()
        }

    var homePage: String
        get() = prefs.getString(HOME_PAGE_KEY, HOME_URL_ALIAS)!!
        set(value) {
            prefs.edit().putString(HOME_PAGE_KEY, value).apply()
        }

    var searchEngineURL = ObservableStringPreference(SearchEnginesURLs[0], SEARCH_ENGINE_URL_PREF_KEY)

    var allowAutoplayMedia: Boolean
        get() = prefs.getBoolean(ALLOW_AUTOPLAY_MEDIA, false)
        set(value) {
            prefs.edit().putBoolean(ALLOW_AUTOPLAY_MEDIA, value).apply()
        }

    var webviewUseAlgorithmicDarkeningWithDarkUiMode: Boolean
        get() = prefs.getBoolean(WEBVIEW_USE_ALGORITHMIC_DARKENING_WITH_DARK_UI_MODE_KEY, true)
        set(value) {
            prefs.edit().putBoolean(WEBVIEW_USE_ALGORITHMIC_DARKENING_WITH_DARK_UI_MODE_KEY, value).apply()
        }

    var webEngineDebug: Boolean
        get() = prefs.getBoolean(WEB_ENGINE_DEBUG_KEY, false)
        set(value) {
            prefs.edit().putBoolean(WEB_ENGINE_DEBUG_KEY, value).apply()
        }

    var initialBookmarksSuggestionsLoaded: Boolean
        get() = prefs.getBoolean(INITIAL_BOOKMARKS_SUGGESTIONS_LOADED, false)
        set(value) {
            prefs.edit().putBoolean(INITIAL_BOOKMARKS_SUGGESTIONS_LOADED, value).apply()
        }

    /**
     * The site view of sites without their own: true for desktop, false for mobile; null follows the
     * interface (desktop on TV, mobile on phones). Can be read from any thread.
     */
    var defaultDesktopView: Boolean?
        get() = if (prefs.contains(DEFAULT_SITE_VIEW_KEY)) prefs.getBoolean(DEFAULT_SITE_VIEW_KEY, true) else null
        set(value) {
            if (value == null) prefs.edit().remove(DEFAULT_SITE_VIEW_KEY).apply()
            else prefs.edit().putBoolean(DEFAULT_SITE_VIEW_KEY, value).apply()
        }

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(ADBLOCK_ENABLED_PREF_KEY, true)
        set(value) {
            prefs.edit().putBoolean(ADBLOCK_ENABLED_PREF_KEY, value).apply()
        }

    /** The engine's own blocking of trackers (GeckoView), on all sites, apart from ad blocking */
    var trackingProtection: Boolean
        get() = prefs.getBoolean(TRACKING_PROTECTION_KEY, true)
        set(value) {
            prefs.edit().putBoolean(TRACKING_PROTECTION_KEY, value).apply()
        }

    var proxyEnabled: Boolean
        get() = prefs.getBoolean(PROXY_ENABLED_KEY, false)
        set(value) {
            prefs.edit().putBoolean(PROXY_ENABLED_KEY, value).apply()
        }

    var proxyType: ProxySettings.Type
        get() = prefs.getString(PROXY_TYPE_KEY, null)
            ?.let { name -> ProxySettings.Type.entries.find { it.name == name } } ?: ProxySettings.Type.HTTP
        set(value) {
            prefs.edit().putString(PROXY_TYPE_KEY, value.name).apply()
        }

    var proxyHost: String
        get() = prefs.getString(PROXY_HOST_KEY, "")!!
        set(value) {
            prefs.edit().putString(PROXY_HOST_KEY, value).apply()
        }

    /** 0 while the user has not given one */
    var proxyPort: Int
        get() = prefs.getInt(PROXY_PORT_KEY, 0)
        set(value) {
            prefs.edit().putInt(PROXY_PORT_KEY, value).apply()
        }

    var proxyUsername: String
        get() = credentialsPrefs.getString(PROXY_USERNAME_KEY, "")!!
        set(value) {
            credentialsPrefs.edit().putString(PROXY_USERNAME_KEY, value).apply()
        }

    var proxyPassword: String
        get() = credentialsPrefs.getString(PROXY_PASSWORD_KEY, "")!!
        set(value) {
            credentialsPrefs.edit().putString(PROXY_PASSWORD_KEY, value).apply()
        }

    /**
     * The proxy to go through: null while it is off or its address is not complete. Only an HTTP proxy has
     * a login.
     */
    val proxy: ProxySettings?
        get() = ProxySettings.of(proxyEnabled, proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword)

    /**
     * The folder the user chose for downloads (a tree uri of the Storage Access Framework), null for the public
     * Downloads directory
     */
    val downloadFolderUri: String?
        get() = prefs.getString(DOWNLOAD_FOLDER_URI_KEY, null)

    /** How the settings show [downloadFolderUri], taken when it was chosen */
    val downloadFolderName: String?
        get() = prefs.getString(DOWNLOAD_FOLDER_NAME_KEY, null)

    /** null [uri] goes back to the public Downloads directory */
    fun setDownloadFolder(uri: String?, name: String?) {
        prefs.edit().apply {
            if (uri == null) remove(DOWNLOAD_FOLDER_URI_KEY).remove(DOWNLOAD_FOLDER_NAME_KEY)
            else putString(DOWNLOAD_FOLDER_URI_KEY, uri).putString(DOWNLOAD_FOLDER_NAME_KEY, name)
        }.apply()
    }

    /** The bookmarks are a channel of the Android TV home screen */
    var channelBookmarks: Boolean
        get() = prefs.getBoolean(CHANNEL_BOOKMARKS_KEY, false)
        set(value) {
            prefs.edit().putBoolean(CHANNEL_BOOKMARKS_KEY, value).apply()
        }

    var channelBookmarksSource: ChannelBookmarksSource
        get() = prefs.getString(CHANNEL_BOOKMARKS_SOURCE_KEY, null)
            ?.let { name -> ChannelBookmarksSource.entries.find { it.name == name } } ?: ChannelBookmarksSource.HOME_PAGE
        set(value) {
            prefs.edit().putString(CHANNEL_BOOKMARKS_SOURCE_KEY, value.name).apply()
        }

    /** The folder of [ChannelBookmarksSource.FOLDER]; 0 is the top level */
    var channelBookmarksFolder: Long
        get() = prefs.getLong(CHANNEL_BOOKMARKS_FOLDER_KEY, 0L)
        set(value) {
            prefs.edit().putLong(CHANNEL_BOOKMARKS_FOLDER_KEY, value).apply()
        }

    /** The open tabs are a channel of the Android TV home screen */
    var channelTabs: Boolean
        get() = prefs.getBoolean(CHANNEL_TABS_KEY, false)
        set(value) {
            prefs.edit().putBoolean(CHANNEL_TABS_KEY, value).apply()
        }

    /** The finished downloads are a channel of the Android TV home screen */
    var channelDownloads: Boolean
        get() = prefs.getBoolean(CHANNEL_DOWNLOADS_KEY, false)
        set(value) {
            prefs.edit().putBoolean(CHANNEL_DOWNLOADS_KEY, value).apply()
        }

    var appWebExtensionVersion: Int
        get() = prefs.getInt(APP_WEB_EXTENSION_VERSION_KEY, 0)
        set(value) {
            prefs.edit().putInt(APP_WEB_EXTENSION_VERSION_KEY, value).apply()
        }

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(AUTO_CHECK_UPDATES_KEY, Utils.isInstalledByAPK(AppContext.get()))
        set(value) {
            prefs.edit().putBoolean(AUTO_CHECK_UPDATES_KEY, value).apply()
        }

    var updateChannel: String
        get() = prefs.getString(UPDATE_CHANNEL_KEY, "release")!!
        set(value) {
            prefs.edit().putString(UPDATE_CHANNEL_KEY, value).apply()
        }

    var appVersionCodeMark: Int
        get() = prefs.getInt(APP_VERSION_CODE_MARK_KEY, 0)
        set(value) {
            prefs.edit().putInt(APP_VERSION_CODE_MARK_KEY, value).apply()
        }

    fun guessSearchEngineName(): String {
        val url = searchEngineURL.value
        val index = SearchEnginesURLs.indexOf(url)
        return if (index != -1 && index < SearchEnginesNames.size)
            SearchEnginesNames[index] else SearchEnginesNames[SearchEnginesNames.size - 1]
    }

    inner class ObservableStringPreference(default: String, private val prefsKey: String) : ObservableValue<String>(default) {
        override var value: String = prefs.getString(prefsKey, default)!!
            set(value) {
                prefs.edit().putString(prefsKey, value).apply()
                field = value
                notifyObservers()
            }
    }
}
