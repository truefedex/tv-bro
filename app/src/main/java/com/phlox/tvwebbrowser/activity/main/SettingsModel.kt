package com.phlox.tvwebbrowser.activity.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.utils.UpdateChecker
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.utils.sameDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class SettingsModel : ActiveModel() {
    companion object {
        val TAG = SettingsModel::class.java.simpleName
        const val TV_BRO_UA_PREFIX = "TV Bro/1.0 "
    }

    val config = TVBro.config

    //Home page settings
    var homePage by config::homePage
    var homePageMode by config::homePageMode
    var homePageLinksMode by config::homePageLinksMode
    //User agent strings configuration
    val userAgentStringTitles = arrayOf("Default (recommended)", "Chrome (Desktop)", "Chrome (Mobile)", "Chrome (Tablet)", "Firefox (Desktop)", "Firefox (Tablet)", "Edge (Desktop)", "Safari (Desktop)", "Safari (iPad)", "Apple TV", "Custom")
    val uaStrings = listOf("",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.89 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.89 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 8.0; Pixel 2 Build/OPD3.170816.012) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.89 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:78.0) Gecko/20100101 Firefox/78.0",
            "Mozilla/5.0 (Android 10; Tablet; rv:68.0) Gecko/68.0 Firefox/68.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.89 Safari/537.36 Edg/84.0.522.44",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.1 Safari/605.1.15",
            "Mozilla/5.0 (iPad; CPU OS 12_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/12.1 Mobile/15E148 Safari/604.1",
            "AppleTV6,2/11.1",
            "")
    //Version & updates configuration
    var needToShowUpdateDlgAgain: Boolean = false
    val updateChecker = UpdateChecker(BuildConfig.VERSION_CODE)
    var lastUpdateNotificationTime: Calendar
    var needAutoCheckUpdates: Boolean
        get() = config.isNeedAutoCheckUpdates() && BuildConfig.BUILT_IN_AUTO_UPDATE
        set(value) = config.setAutoCheckUpdates(value)
    var theme by config::theme
    var updateChannel: String
    var keepScreenOn = object : ObservableValue<Boolean>(config.keepScreenOn) {
        override var value: Boolean = config.keepScreenOn
            set(value) {
                config.keepScreenOn = value
                field = value
                notifyObservers()
            }
            get() = config.keepScreenOn
    }//makeObservable(config::keepScreenOn)

    init {
        lastUpdateNotificationTime = if (config.prefs.contains(Config.LAST_UPDATE_USER_NOTIFICATION_TIME_KEY))
            Calendar.getInstance().apply { timeInMillis = config.prefs.getLong(Config.LAST_UPDATE_USER_NOTIFICATION_TIME_KEY, 0) } else
            Calendar.getInstance()
        updateChannel = config.getUpdateChannel()
    }

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

    fun saveAutoCheckUpdates(need: Boolean) {
        config.setAutoCheckUpdates(need)
    }

    fun saveUpdateChannel(selectedChannel: String) {
        config.setUpdateChannel(selectedChannel)
        updateChannel = selectedChannel
    }

    fun checkUpdate(force: Boolean, onDoneCallback: () -> Unit) = modelScope.launch(Dispatchers.Main) {
        if (updateChecker.versionCheckResult == null || force) {
            launch(Dispatchers.IO) {
                try {
                    updateChecker.check("https://raw.githubusercontent.com/truefedex/tv-bro/master/latest_version.json",
                            arrayOf(updateChannel))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.join()
        }
        onDoneCallback()
    }

    fun showUpdateDialogIfNeeded(activity: MainActivity, force: Boolean = false) {
        val now = Calendar.getInstance()
        if (lastUpdateNotificationTime.sameDay(now) && !force) {
            return
        }
        if (!updateChecker.hasUpdate()) {
            throw IllegalStateException()
        }
        lastUpdateNotificationTime = now
        config.prefs.edit()
                .putLong(Config.LAST_UPDATE_USER_NOTIFICATION_TIME_KEY, lastUpdateNotificationTime.timeInMillis)
                .apply()

        updateChecker.showUpdateDialog(activity, object : UpdateChecker.DialogCallback {
            override fun download() {
                if (activity.isFinishing) return
                updateChecker.versionCheckResult ?: return

                val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.packageManager.canRequestPackageInstalls()
                } else
                    Settings.Secure.getInt(activity.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1

                if (canInstallFromOtherSources) {
                    updateChecker.downloadUpdate(activity)
                } else {
                    AlertDialog.Builder(activity)
                            .setTitle(R.string.app_name)
                            .setMessage(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                R.string.turn_on_unknown_sources_for_app else R.string.turn_on_unknown_sources)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val intent = Intent()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    intent.action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                                    intent.data = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                                } else {
                                    intent.action = Settings.ACTION_SECURITY_SETTINGS
                                }
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                                try {
                                    activity.startActivityForResult(intent, MainActivity.REQUEST_CODE_UNKNOWN_APP_SOURCES)
                                    needToShowUpdateDlgAgain = true
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->  }
                            .show()
                }
            }

            override fun later() {}

            override fun settings() {
                if (!activity.isFinishing) {
                    activity.showSettings()
                }
            }
        })
    }
}
