package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.core.net.toUri
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.main.AutoUpdateModel
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity
import com.phlox.tvwebbrowser.webengine.WebEngineFactory

/**
 * "About" settings category. Renders app info, links and update controls as native TV-settings rows.
 */
class VersionSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SettingsPageView(context, attrs, defStyleAttr) {

    companion object {
        private const val URL_PROJECT = "https://github.com/truefedex/tv-bro"
        private const val URL_SUPPORT_AUTHOR = "https://donatello.to/truefedex"
        private const val URL_LICENSE =
            "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/LICENSE.md"
        private const val URL_PRIVACY_POLICY =
            "https://raw.githubusercontent.com/truefedex/tv-bro/refs/heads/master/PRIVACY.md"
        private const val URL_ICON_STORY = "https://tv-bro-3546c.web.app/msg001.html"
    }

    private val config = AppContext.provideConfig()
    private val settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)
    private val autoUpdateModel = ActiveModelsRepository.get(AutoUpdateModel::class, activity!!)
    var callback: Callback? = null

    interface Callback {
        fun onNeedToCloseSettings()
    }

    init {
        setItems(buildItems())
        ensureUpdateInfo()
    }

    private fun buildItems(): List<SettingsItem> = mutableListOf<SettingsItem>().apply {
        add(SettingsItem.Header(R.string.settings_section_about_app))
        add(
            SettingsItem.Info(
                iconRes = R.drawable.ic_settings_tv_24dp,
                titleRes = R.string.app_name_short,
                value = { context.getString(R.string.web_browser_optimized_for_tvs) }
            )
        )
        add(
            SettingsItem.Info(
                iconRes = R.drawable.ic_settings_info_24dp,
                titleRes = R.string.settings_version,
                value = { BuildConfig.VERSION_NAME }
            )
        )
        add(
            SettingsItem.Info(
                iconRes = R.drawable.ic_settings_build_24dp,
                titleRes = R.string.settings_build_flavor,
                value = {
                    context.getString(
                        R.string.settings_build_flavor_value_s,
                        BuildConfig.FLAVOR_appstore,
                        BuildConfig.FLAVOR_webengine
                    )
                }
            )
        )
        add(
            SettingsItem.Info(
                iconRes = R.drawable.ic_settings_devices_24dp,
                titleRes = R.string.settings_engine_version,
                value = { WebEngineFactory.getWebEngineVersionString() }
            )
        )

        add(SettingsItem.Header(R.string.settings_section_links))
        add(
            SettingsItem.Action(
                iconRes = R.drawable.ic_settings_code_24dp,
                titleRes = R.string.settings_github,
                onClick = { loadUrl(URL_PROJECT) }
            )
        )
        add(
            SettingsItem.Action(
                iconRes = R.drawable.ic_settings_favorite_24dp,
                titleRes = R.string.settings_support_author,
                onClick = { loadUrl(URL_SUPPORT_AUTHOR) }
            )
        )
        add(
            SettingsItem.Action(
                iconRes = R.drawable.ic_settings_description_24dp,
                titleRes = R.string.settings_license,
                onClick = { loadUrl(URL_LICENSE) }
            )
        )
        add(
            SettingsItem.Action(
                iconRes = R.drawable.ic_settings_shield_24dp,
                titleRes = R.string.settings_privacy,
                onClick = { loadUrl(URL_PRIVACY_POLICY) }
            )
        )
        add(
            SettingsItem.Action(
                iconRes = R.drawable.ic_settings_flag_24dp,
                titleRes = R.string.settings_icon_story,
                onClick = { loadUrl(URL_ICON_STORY) }
            )
        )

        if (BuildConfig.BUILT_IN_AUTO_UPDATE) {
            add(SettingsItem.Header(R.string.settings_section_updates))
            add(
                SettingsItem.Toggle(
                    iconRes = R.drawable.ic_refresh_grey_900_24dp,
                    titleRes = R.string.auto_check_for_updates,
                    isChecked = { autoUpdateModel.needAutoCheckUpdates },
                    onCheckedChange = { enabled ->
                        autoUpdateModel.saveAutoCheckUpdates(enabled)
                        if (enabled) ensureUpdateInfo()
                        else post { rebuild() }
                    }
                )
            )
            add(
                SettingsItem.Choice(
                    iconRes = R.drawable.ic_settings_download_24dp,
                    titleRes = R.string.settings_update_channel,
                    value = { config.updateChannel },
                    entries = { availableChannels().toList() },
                    selectedIndex = { availableChannels().indexOf(config.updateChannel).coerceAtLeast(0) },
                    onSelected = { position ->
                        val channel = availableChannels().getOrNull(position)
                        if (channel != null && channel != config.updateChannel) {
                            config.updateChannel = channel
                            autoUpdateModel.checkUpdate(true) { post { rebuild() } }
                        } else {
                            post { rebuild() }
                        }
                    },
                    visible = { autoUpdateModel.needAutoCheckUpdates }
                )
            )
            add(
                SettingsItem.Info(
                    iconRes = R.drawable.ic_settings_download_24dp,
                    titleRes = R.string.settings_new_version,
                    value = {
                        autoUpdateModel.updateChecker.versionCheckResult?.latestVersionName ?: ""
                    },
                    visible = { hasUpdate() }
                )
            )
            add(
                SettingsItem.Action(
                    iconRes = R.drawable.ic_settings_download_24dp,
                    titleRes = R.string.settings_install_update,
                    onClick = {
                        callback?.onNeedToCloseSettings()
                        autoUpdateModel.showUpdateDialogIfNeeded(activity as MainActivity, true)
                    },
                    visible = { hasUpdate() }
                )
            )
        }
    }

    private fun availableChannels(): Array<String> =
        autoUpdateModel.updateChecker.versionCheckResult?.availableChannels ?: emptyArray()

    private fun hasUpdate(): Boolean =
        autoUpdateModel.needAutoCheckUpdates && autoUpdateModel.updateChecker.hasUpdate()

    private fun ensureUpdateInfo() {
        if (!BuildConfig.BUILT_IN_AUTO_UPDATE) return
        if (autoUpdateModel.updateChecker.versionCheckResult != null) return
        autoUpdateModel.checkUpdate(false) { post { rebuild() } }
    }

    private fun loadUrl(url: String) {
        callback?.onNeedToCloseSettings()
        val activityClass = if (settingsModel.config.incognitoMode)
            IncognitoModeMainActivity::class.java else MainActivity::class.java
        val intent = Intent(activity, activityClass)
        intent.data = url.toUri()
        activity?.startActivity(intent)
    }
}
