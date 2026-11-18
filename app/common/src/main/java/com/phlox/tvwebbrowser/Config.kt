package com.phlox.tvwebbrowser

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.webengine.SiteView

/**
 * The settings of the app. [credentialsPrefs] keeps the secrets (the login of the proxy): its file is left out
 * of backups.
 */
class Config(val prefs: SharedPreferences, private val credentialsPrefs: SharedPreferences = prefs) {
    companion object {
        const val SEARCH_ENGINE_URL_PREF_KEY = "search_engine_url"
        const val SEARCH_ENGINE_AS_HOME_PAGE_KEY = "search_engine_as_home_page"
        const val SEARCH_SUGGESTIONS_KEY = "search_suggestions"
        const val CUSTOM_SEARCH_SUGGEST_URL_KEY = "custom_search_suggest_url"
        /** The host of the search engine [CUSTOM_SEARCH_SUGGEST_URL_KEY] was given for */
        const val CUSTOM_SEARCH_SUGGEST_HOST_KEY = "custom_search_suggest_host"
        const val HOME_PAGE_KEY = "home_page"
        /** The default site view of older versions: true for desktop, false for mobile */
        const val LEGACY_DEFAULT_SITE_VIEW_KEY = "default_desktop_view"
        const val DEFAULT_SITE_VIEW_KEY = "default_site_view"
        const val USER_AGENT_KEY = "user_agent"
        /** In the User-Agents of old versions, which should not be kept */
        private const val LEGACY_USER_AGENT_PREFIX = "TV Bro/1.0 "
        const val THEME_KEY = "theme"
        const val INTERFACE_MODE_KEY = "interface_mode"
        const val LAST_UPDATE_USER_NOTIFICATION_TIME_KEY = "last_update_notif"
        const val AUTO_CHECK_UPDATES_KEY = "auto_check_updates"
        const val UPDATE_CHANNEL_KEY = "update_channel"
        const val HOME_URL_ALIAS = "about:home"
        const val KEEP_SCREEN_ON_KEY = "keep_screen_on"
        const val MATCH_FRAME_RATE_KEY = "match_frame_rate"
        const val PAGE_VIDEO_HINT_KEY = "page_video_hint"
        /** When true, analog stick / hat axes from generic motion events are not translated to DPAD keys. */
        const val DISABLE_MOTION_AXES_DPAD_NAVIGATION_KEY = "disable_motion_axes_dpad_navigation"
        /** Percent of built-in default (100 = default). Range [CURSOR_PHYSICS_PERCENT_MIN], [CURSOR_PHYSICS_PERCENT_MAX]. */
        const val CURSOR_MAX_SPEED_PERCENT_KEY = "cursor_max_speed_percent"
        const val CURSOR_ACCELERATION_PERCENT_KEY = "cursor_acceleration_percent"
        const val CURSOR_PHYSICS_PERCENT_MIN = 25
        const val CURSOR_PHYSICS_PERCENT_MAX = 200
        const val INCOGNITO_MODE_KEY = "incognito_mode"
        const val INCOGNITO_MODE_HINT_SUPPRESS_KEY = "incognito_mode_hint_suppress"
        /** "Don't show again" of the dialog that told about the built-in player before its toast ([builtInPlayerIntroShown]) */
        const val BUILT_IN_PLAYER_HINT_SUPPRESS_KEY = "built_in_player_hint_suppress"
        const val BUILT_IN_PLAYER_INTRO_SHOWN_KEY = "built_in_player_intro_shown"
        const val BUILT_IN_PLAYER_BY_DEFAULT_KEY = "built_in_player_by_default"
        /** The toast about the built-in player shows the first times it opens */
        const val BUILT_IN_PLAYER_INTRO_TIMES = 3
        const val DIRECT_NAVIGATION_MODE_HINT_SUPPRESS_KEY = "direct_navigation_mode_hint_suppress"
        const val HOME_PAGE_MODE = "home_page_mode"
        const val HOME_PAGE_SUGGESTIONS_MODE = "home_page_suggestions_mode"
        const val HOME_PAGE_BOOKMARKS_FOLDER_KEY = "home_page_bookmarks_folder"
        /** [homePageBookmarksFolder]: the Home page folder of the bookmarks */
        const val HOME_PAGE_BOOKMARKS_OWN_FOLDER = -1L
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
        const val HISTORY_RETENTION_KEY = "history_retention"
        /** The prefs of [credentialsPrefs] (the file `credentials.xml`), left out of backups (`backup_rules.xml`) */
        const val CREDENTIALS_PREFS_NAME = "credentials"

        /** The pref of the version of [DEFAULT_SEARCH_ENGINE_VERSION] the search engine was last set by */
        const val DEFAULT_SEARCH_ENGINE_VERSION_KEY = "default_search_engine_v"
        /** Raised when the default search engine of some users changes, so it is chosen for them again once */
        const val DEFAULT_SEARCH_ENGINE_VERSION = 1

        /**
         * The partner ID of Qwant, the `client` parameter of its searches, given to the build (`qwantClientId`):
         * not in the repository, so forks do not send it; empty in builds without it
         */
        val QWANT_CLIENT_ID: String = com.phlox.tvwebbrowser.common.BuildConfig.QWANT_CLIENT_ID
        /** The build has the partner ID of Qwant: Qwant is the default search engine in the partner countries */
        val QWANT_PARTNER = QWANT_CLIENT_ID.isNotEmpty()
        private val QWANT_CLIENT_PARAM = if (QWANT_PARTNER) "&client=$QWANT_CLIENT_ID" else ""
        /** The address of Qwant's searches without the partner ID */
        private const val QWANT_SEARCH_URL = "https://www.qwant.com/?q=[query]&t=web"

        val SearchEnginesTitles = arrayOf("Google", "Bing", "Yahoo!", "DuckDuckGo", "Yandex", "Startpage", "Qwant", "Custom")
        val SearchEnginesNames = arrayOf("google", "bing", "yahoo", "ddg", "yandex", "startpage", "qwant", "custom")
        val SearchEnginesURLs = listOf("https://www.google.com/search?q=[query]", "https://www.bing.com/search?q=[query]",
            "https://search.yahoo.com/search?p=[query]", "https://duckduckgo.com/?q=[query]",
            "https://yandex.com/search/?text=[query]", "https://www.startpage.com/sp/search?query=[query]",
            QWANT_SEARCH_URL + QWANT_CLIENT_PARAM, "")
        const val QWANT_INDEX = 6
        /**
         * The page each search engine opens as the home page (the home page mode of the search engine); empty: the
         * origin of its search address
         */
        val SearchEnginesHomeURLs = listOf("", "", "", "", "", "",
            if (QWANT_PARTNER) "https://www.qwant.com/?client=$QWANT_CLIENT_ID" else "https://www.qwant.com/", "")
        /** In the addresses of suggestions: the language and the country of the user, as fr_fr ([QWANT_INDEX]) */
        const val SUGGEST_LANG = "[lang]"
        /**
         * Where each search engine gives the suggestions of a text ("[query]"), as OpenSearch suggestions:
         * a JSON array of the text and the array of the suggestions
         */
        val SearchEnginesSuggestURLs = listOf(
            "https://suggestqueries.google.com/complete/search?client=firefox&q=[query]",
            "https://api.bing.com/osjson.aspx?query=[query]",
            "https://ff.search.yahoo.com/gossip?output=fxjson&command=[query]",
            "https://duckduckgo.com/ac/?q=[query]&type=list",
            "https://suggest.yandex.com/suggest-ff.cgi?part=[query]",
            "https://www.startpage.com/osuggestions?q=[query]",
            //client=opensearch is the format of the answer, not the partner ID
            "https://api.qwant.com/api/suggest/?q=[query]&client=opensearch&lang=$SUGGEST_LANG", "")
        const val HOME_PAGE_URL = "https://tvbro.phlox.dev/appcontent/home/"

        /** The host of the address of a search engine (with "[query]") in lower case, null when it has none */
        fun searchEngineHost(url: String): String? = try {
            //the placeholder is not a legal part of a URI
            java.net.URI(url.trim().replace("[query]", "q").replace(SUGGEST_LANG, "l")).host?.lowercase()?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
        //const val HOME_PAGE_URL = "http://10.0.2.2:5000/appcontent/home/"

        /**
         * The address of an engine of the list for the forms of it the app itself made: Qwant's without the partner
         * ID (the home page sends it) or with another one (a value of an older build); other addresses as they are
         */
        fun canonicalSearchEngineUrl(url: String): String {
            if (url == QWANT_SEARCH_URL) return SearchEnginesURLs[QWANT_INDEX]
            val clientPrefix = "$QWANT_SEARCH_URL&client="
            if (url.startsWith(clientPrefix)) {
                val client = url.substring(clientPrefix.length)
                if (client.isNotEmpty() && client.all { it.isLetterOrDigit() || it in "-_." }) {
                    return SearchEnginesURLs[QWANT_INDEX]
                }
            }
            return url
        }

        /**
         * The address of the search engine the built-in home page chose: that of the list for a name of it (the page
         * knows Qwant without the partner ID), the [url] it sent for the user's own engine
         */
        fun searchEngineUrlOfHomePage(engine: String, url: String): String {
            val index = SearchEnginesNames.indexOf(engine)
            return if (index >= 0 && SearchEnginesURLs[index].isNotEmpty()) SearchEnginesURLs[index] else url
        }

        /** The page the search engine of [searchUrl] opens as the home page, null when it has none */
        fun searchEngineHomeUrl(searchUrl: String): String? {
            val index = SearchEnginesURLs.indexOf(searchUrl)
            if (index >= 0 && SearchEnginesHomeURLs[index].isNotEmpty()) return SearchEnginesHomeURLs[index]
            return """^https?://[^#?/]+""".toRegex().find(searchUrl)?.value
        }
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

    /** How long a visit stays in the browsing history: [days] */
    enum class HistoryRetention(val days: Int) {
        WEEK(7), MONTH(30), THREE_MONTHS(90), SIX_MONTHS(180), YEAR(365)
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

    /**
     * How many times the toast about the built-in player showed when it opened; it shows the first
     * [BUILT_IN_PLAYER_INTRO_TIMES] times. Users who chose "Don't show again" for the dialog it replaced know the player.
     */
    var builtInPlayerIntroShown: Int
        get() = if (prefs.getBoolean(BUILT_IN_PLAYER_HINT_SUPPRESS_KEY, false)) BUILT_IN_PLAYER_INTRO_TIMES
            else prefs.getInt(BUILT_IN_PLAYER_INTRO_SHOWN_KEY, 0)
        set(value) {
            prefs.edit().putInt(BUILT_IN_PLAYER_INTRO_SHOWN_KEY, value).apply()
        }

    /**
     * Videos that pages put in fullscreen go to the built-in player by themselves (true) or stay in the player of the
     * site (false), on TV; a site may have its own choice ([com.phlox.tvwebbrowser.model.HostConfig.builtInPlayer])
     */
    var builtInPlayerByDefault: Boolean
        get() = prefs.getBoolean(BUILT_IN_PLAYER_BY_DEFAULT_KEY, true)
        set(value) {
            prefs.edit().putBoolean(BUILT_IN_PLAYER_BY_DEFAULT_KEY, value).apply()
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

    /**
     * The folder whose bookmarks the built-in home page shows in [HomePageLinksMode.BOOKMARKS]:
     * [HOME_PAGE_BOOKMARKS_OWN_FOLDER] (the Home page folder, the default), 0 for the top level, else the id
     * of a folder
     */
    var homePageBookmarksFolder: Long
        get() = prefs.getLong(HOME_PAGE_BOOKMARKS_FOLDER_KEY, HOME_PAGE_BOOKMARKS_OWN_FOLDER)
        set(value) {
            prefs.edit().putLong(HOME_PAGE_BOOKMARKS_FOLDER_KEY, value).apply()
        }

    var homePage: String
        get() = prefs.getString(HOME_PAGE_KEY, HOME_URL_ALIAS)!!
        set(value) {
            prefs.edit().putString(HOME_PAGE_KEY, value).apply()
        }

    var searchEngineURL = object : ObservableValue<String>(SearchEnginesURLs[0]) {
        override var value: String = canonicalSearchEngineUrl(prefs.getString(SEARCH_ENGINE_URL_PREF_KEY, SearchEnginesURLs[0])!!)
            set(value) {
                val url = canonicalSearchEngineUrl(value)
                prefs.edit().putString(SEARCH_ENGINE_URL_PREF_KEY, url).apply()
                field = url
                notifyObservers()
            }
    }

    /**
     * Sets the search engine ([searchEngineURL]) and, while the home page is that of the search engine
     * ([HomePageMode.SEARCH_ENGINE]), the home page too
     */
    fun setSearchEngine(url: String) {
        searchEngineURL.value = url
        if (homePageMode == HomePageMode.SEARCH_ENGINE) {
            homePage = searchEngineHomeUrl(searchEngineURL.value) ?: HOME_URL_ALIAS
        }
    }

    /** The user (or the app) has saved a search engine; else [searchEngineURL] is the first of the list */
    val searchEngineSaved: Boolean
        get() = prefs.contains(SEARCH_ENGINE_URL_PREF_KEY)

    /** The [DEFAULT_SEARCH_ENGINE_VERSION] the default search engine was last chosen by, 0 never */
    var defaultSearchEngineVersion: Int
        get() = prefs.getInt(DEFAULT_SEARCH_ENGINE_VERSION_KEY, 0)
        set(value) {
            prefs.edit().putInt(DEFAULT_SEARCH_ENGINE_VERSION_KEY, value).apply()
        }

    /**
     * The text typed into the address field is sent to the search engine, which suggests searches for it
     * (never in incognito mode)
     */
    var searchSuggestions: Boolean
        get() = prefs.getBoolean(SEARCH_SUGGESTIONS_KEY, true)
        set(value) {
            prefs.edit().putBoolean(SEARCH_SUGGESTIONS_KEY, value).apply()
        }

    /**
     * Where the user's own search engine gives its suggestions, with "[query]" in place of the text; blank
     * when it has none. It is kept for the site of the search engine it was given for: blank again once the
     * engine ([searchEngineURL]) is of another host.
     */
    var customSearchSuggestUrl: String
        get() {
            val url = prefs.getString(CUSTOM_SEARCH_SUGGEST_URL_KEY, "")!!
            if (url.isEmpty()) return ""
            val host = searchEngineHost(searchEngineURL.value)
            return if (host != null && host == prefs.getString(CUSTOM_SEARCH_SUGGEST_HOST_KEY, null)) url else ""
        }
        set(value) {
            prefs.edit().putString(CUSTOM_SEARCH_SUGGEST_URL_KEY, value.trim())
                .putString(CUSTOM_SEARCH_SUGGEST_HOST_KEY, searchEngineHost(searchEngineURL.value))
                .apply()
        }

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
     * The site view of sites without their own; null follows the interface (tablet on TV, mobile on phones).
     * The choice of older versions (desktop or mobile) is kept. Can be read from any thread.
     */
    var defaultSiteView: SiteView?
        get() = when {
            prefs.contains(DEFAULT_SITE_VIEW_KEY) -> SiteView.ofCode(prefs.getInt(DEFAULT_SITE_VIEW_KEY, -1))
            prefs.contains(LEGACY_DEFAULT_SITE_VIEW_KEY) ->
                if (prefs.getBoolean(LEGACY_DEFAULT_SITE_VIEW_KEY, true)) SiteView.DESKTOP else SiteView.MOBILE
            else -> null
        }
        set(value) {
            val editor = prefs.edit().remove(LEGACY_DEFAULT_SITE_VIEW_KEY)
            if (value == null) editor.remove(DEFAULT_SITE_VIEW_KEY) else editor.putInt(DEFAULT_SITE_VIEW_KEY, value.code)
            editor.apply()
        }

    /**
     * The User-Agent of all pages in place of the one of their site view; null (Auto): the one of the view.
     * Blank strings are null.
     */
    var userAgent: String?
        get() = prefs.getString(USER_AGENT_KEY, null)
            ?.takeIf { it.isNotBlank() && !it.contains(LEGACY_USER_AGENT_PREFIX) }
        set(value) {
            val userAgent = value?.trim()?.ifEmpty { null }
            if (userAgent == null) prefs.edit().remove(USER_AGENT_KEY).apply()
            else prefs.edit().putString(USER_AGENT_KEY, userAgent).apply()
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

    /** Visits older than this are deleted from the browsing history ([HistoryRetention.YEAR] by default) */
    var historyRetention: HistoryRetention
        get() = prefs.getString(HISTORY_RETENTION_KEY, null)
            ?.let { name -> HistoryRetention.entries.find { it.name == name } } ?: HistoryRetention.YEAR
        set(value) {
            prefs.edit().putString(HISTORY_RETENTION_KEY, value.name).apply()
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
}
