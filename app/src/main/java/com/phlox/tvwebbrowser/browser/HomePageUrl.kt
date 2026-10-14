package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.Config

/**
 * Turns the [Config.HOME_URL_ALIAS] into the url of the home page chosen in the settings.
 * Engines get only real urls.
 */
object HomePageUrl {
    const val BLANK_PAGE_URL = "about:blank"

    fun resolve(url: String, config: Config): String {
        if (url != Config.HOME_URL_ALIAS) return url
        return when (config.homePageMode) {
            Config.HomePageMode.BLANK -> BLANK_PAGE_URL
            Config.HomePageMode.HOME_PAGE -> Config.HOME_PAGE_URL
            Config.HomePageMode.CUSTOM, Config.HomePageMode.SEARCH_ENGINE -> {
                val homePage = config.homePage
                //the alias is stored when there is no custom url (or it can not be taken from the search engine one)
                if (homePage == Config.HOME_URL_ALIAS) Config.HOME_PAGE_URL else homePage
            }
        }
    }
}
