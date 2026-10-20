package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.observable.ObservableValue

class SettingsModel : ActiveModel() {
    companion object {
        val TAG = SettingsModel::class.java.simpleName
    }

    val config = AppContext.provideConfig()

    //Home page settings
    var homePage by config::homePage
    var homePageMode by config::homePageMode
    var homePageLinksMode by config::homePageLinksMode

    var keepScreenOn = object : ObservableValue<Boolean>(config.keepScreenOn) {
        override var value: Boolean = config.keepScreenOn
            set(value) {
                config.keepScreenOn = value
                field = value
                notifyObservers()
            }
            get() = config.keepScreenOn
    }//makeObservable(config::keepScreenOn)

    fun setSearchEngineURL(url: String) {
        config.searchEngineURL.value = url
        if (homePageMode == Config.HomePageMode.SEARCH_ENGINE) {
            updateHomeAsSearchEngine(url)
        }
    }

    private fun updateHomeAsSearchEngine(url: String) {
        val regexForUrl = """^https?://[^#?/]+""".toRegex()
        val homePageUrl = regexForUrl.find(url)?.value ?: Config.HOME_URL_ALIAS
        homePage = homePageUrl
    }

    fun setHomePageProperties(homePageMode: Config.HomePageMode, customHomePageUrl: String?, homePageLinksMode: Config.HomePageLinksMode) {
        this.homePageMode = homePageMode
        this.homePageLinksMode = homePageLinksMode
        when (homePageMode) {
            Config.HomePageMode.SEARCH_ENGINE -> {
                updateHomeAsSearchEngine(config.searchEngineURL.value)
            }
            Config.HomePageMode.HOME_PAGE, Config.HomePageMode.BLANK -> {
                homePage = Config.HOME_URL_ALIAS
            }
            Config.HomePageMode.CUSTOM -> {
                homePage = customHomePageUrl ?: Config.HOME_URL_ALIAS
            }
        }
    }
}
