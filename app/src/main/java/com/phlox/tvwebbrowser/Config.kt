package com.phlox.tvwebbrowser

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import org.mozilla.geckoview.GeckoRuntimeSettings

class Config(val prefs: SharedPreferences) {
    companion object {
        const val SEARCH_ENGINE_URL_PREF_KEY = "search_engine_url"
        const val SEARCH_ENGINE_AS_HOME_PAGE_KEY = "search_engine_as_home_page"
        const val HOME_PAGE_KEY = "home_page"
        const val USER_AGENT_PREF_KEY = "user_agent"
        const val THEME_KEY = "theme"
        const val LAST_UPDATE_USER_NOTIFICATION_TIME_KEY = "last_update_notif"
        const val AUTO_CHECK_UPDATES_KEY = "auto_check_updates"
        const val UPDATE_CHANNEL_KEY = "update_channel"
        const val TV_BRO_UA_PREFIX = "TV Bro/1.0 "
        const val HOME_URL_ALIAS = "about:home"
        const val KEEP_SCREEN_ON_KEY = "keep_screen_on"
        const val INCOGNITO_MODE_KEY = "incognito_mode"
        const val INCOGNITO_MODE_HINT_SUPPRESS_KEY = "incognito_mode_hint_suppress"
        const val HOME_PAGE_MODE = "home_page_mode"
        const val HOME_PAGE_SUGGESTIONS_MODE = "home_page_suggestions_mode"
        const val WEB_ENGINE = "web_engine"
        const val ALLOW_AUTOPLAY_MEDIA = "allow_autoplay_media"
        //const val HOME_PAGE_VERSION_EXTRACTED = "home_page_version_extracted"
        const val INITIAL_BOOKMARKS_SUGGESTIONS_LOADED = "initial_bookmarks_suggestions_loaded"
        const val ADBLOCK_ENABLED_PREF_KEY = "adblock_enabled"
        const val ADBLOCK_LAST_UPDATE_LIST_KEY = "adblock_last_update"
        const val ADBLOCK_LIST_URL_KEY = "adblock_list_url"
        const val APP_WEB_EXTENSION_VERSION_KEY = "app_web_extension_version"
        const val NOTIFICATION_ABOUT_ENGINE_CHANGE_SHOWN_KEY = "notification_about_engine_change_shown"
        const val APP_VERSION_CODE_MARK_KEY = "app_version_code_mark"

        const val ENGINE_GECKO_VIEW = "GeckoView"
        const val ENGINE_WEB_VIEW = "WebView"

        const val DEFAULT_ADBLOCK_LIST_URL = "https://easylist.to/easylist/easylist.txt"
        val SearchEnginesTitles = arrayOf("Google", "Bing", "Yahoo!", "DuckDuckGo", "Yandex", "Startpage", "Custom")
        val SearchEnginesNames = arrayOf("google", "bing", "yahoo", "ddg", "yandex", "startpage", "custom")
        val SearchEnginesURLs = listOf("https://www.google.com/search?q=[query]", "https://www.bing.com/search?q=[query]",
            "https://search.yahoo.com/search?p=[query]", "https://duckduckgo.com/?q=[query]",
            "https://yandex.com/search/?text=[query]", "https://www.startpage.com/sp/search?query=[query]", "")
        val SupportedWebEngines = arrayOf(ENGINE_GECKO_VIEW, ENGINE_WEB_VIEW)
        const val HOME_PAGE_URL = "https://tvbro.phlox.dev/appcontent/home/"
        //const val HOME_PAGE_URL = "http://10.0.2.2:5000/appcontent/home/"

        fun canRecommendGeckoView(): Boolean {
            var recommendedGeckoView = false
            val deviceRAM = Utils.memInfo(TVBro.instance).totalMem
            val cpuHas64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val threeGB = 3000000000L//~3GB minus protected memory
            if (deviceRAM >= threeGB && cpuHas64Bit && cpuCores >= 6) {
                recommendedGeckoView = true
            }
            return recommendedGeckoView
        }
    }

    enum class Theme {
        SYSTEM, WHITE, BLACK;

        fun toGeckoPreferredColorScheme(): Int {
            return when (this) {
                SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
                WHITE -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                BLACK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
            }
        }
    }

    enum class HomePageMode {
        HOME_PAGE, SEARCH_ENGINE, CUSTOM, BLANK
    }

    enum class HomePageLinksMode {
        BOOKMARKS, LATEST_HISTORY, MOST_VISITED
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

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON_KEY, false)
        set(value) {
            prefs.edit().putBoolean(KEEP_SCREEN_ON_KEY, value).apply()
        }

    var theme = object : ObservableValue<Theme>(Theme.SYSTEM) {
        override var value: Theme = Theme.values()[prefs.getInt(THEME_KEY, 0)]
            set(value) {
                prefs.edit().putInt(THEME_KEY, value.ordinal).apply()
                field = value
                notifyObservers()
            }
    }

    var homePageMode: HomePageMode
        get() = prefs.getInt(HOME_PAGE_MODE, 0)
            .let {
                //ignore value if search engine as home page is set
                if (prefs.getBoolean(SEARCH_ENGINE_AS_HOME_PAGE_KEY, false)) {
                    prefs.edit().remove(SEARCH_ENGINE_AS_HOME_PAGE_KEY).apply()
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

    var webEngine: String
        get() {
            if (!prefs.contains(WEB_ENGINE)) {
                prefs.edit().putString(WEB_ENGINE, if (canRecommendGeckoView())
                    SupportedWebEngines[0] else SupportedWebEngines[1]).apply()
            }

            return prefs.getString(WEB_ENGINE, SupportedWebEngines[0])!!
        }
        set(value) {
            prefs.edit().putString(WEB_ENGINE, value).apply()
        }

    var allowAutoplayMedia: Boolean
        get() = prefs.getBoolean(ALLOW_AUTOPLAY_MEDIA, false)
        set(value) {
            prefs.edit().putBoolean(ALLOW_AUTOPLAY_MEDIA, value).apply()
        }

    /*var homePageVersionExtracted: Int
        get() = prefs.getInt(HOME_PAGE_VERSION_EXTRACTED, 0)
        set(value) {
            prefs.edit().putInt(HOME_PAGE_VERSION_EXTRACTED, value).apply()
        }*/

    var initialBookmarksSuggestionsLoaded: Boolean
        get() = prefs.getBoolean(INITIAL_BOOKMARKS_SUGGESTIONS_LOADED, false)
        set(value) {
            prefs.edit().putBoolean(INITIAL_BOOKMARKS_SUGGESTIONS_LOADED, value).apply()
        }

    var userAgentString = ObservableOptStringPreference(null, USER_AGENT_PREF_KEY)

    var adBlockEnabled: Boolean = prefs.getBoolean(ADBLOCK_ENABLED_PREF_KEY, true)
        set(value) {
            field = value
            prefs.edit().putBoolean(ADBLOCK_ENABLED_PREF_KEY, field).apply()
        }

    var adBlockListURL = ObservableStringPreference(DEFAULT_ADBLOCK_LIST_URL, ADBLOCK_LIST_URL_KEY)

    var adBlockListLastUpdate: Long
        get() = prefs.getLong(ADBLOCK_LAST_UPDATE_LIST_KEY, 0)
        set(value) {
            prefs.edit().putLong(ADBLOCK_LAST_UPDATE_LIST_KEY, value).apply()
        }

    var appWebExtensionVersion: Int
        get() = prefs.getInt(APP_WEB_EXTENSION_VERSION_KEY, 0)
        set(value) {
            prefs.edit().putInt(APP_WEB_EXTENSION_VERSION_KEY, value).apply()
        }

    //0 - not shown, any other value - app version code where notification already shown (to show only once after update)
    var notificationAboutEngineChangeShown: Int
        get() = prefs.getInt(NOTIFICATION_ABOUT_ENGINE_CHANGE_SHOWN_KEY, 0)
        set(value) {
            prefs.edit().putInt(NOTIFICATION_ABOUT_ENGINE_CHANGE_SHOWN_KEY, value).apply()
        }

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(AUTO_CHECK_UPDATES_KEY, Utils.isInstalledByAPK(TVBro.instance))
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

    fun isWebEngineGecko(): Boolean {
        return webEngine == SupportedWebEngines[0]
    }

    fun isWebEngineNotSet(): Boolean {
        return !prefs.contains(WEB_ENGINE)
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

    inner class ObservableOptStringPreference(default: String?, private val prefsKey: String) : ObservableValue<String?>(default) {
        override var value: String? = prefs.getString(prefsKey, default)
            set(value) {
                if (value == null) prefs.edit().remove(prefsKey).apply()
                else prefs.edit().putString(prefsKey, value).apply()
                field = value
                notifyObservers()
            }
    }
}
