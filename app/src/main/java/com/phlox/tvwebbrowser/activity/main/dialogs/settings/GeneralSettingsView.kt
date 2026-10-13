package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity

class GeneralSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SettingsPageView(context, attrs, defStyleAttr) {

    private val settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)
    private val config = AppContext.provideConfig()

    private val engineTitles get() = Config.SearchEnginesTitles.toList()

    init {
        // Legacy UA string - now the default one should be used instead.
        if (config.userAgentString.value?.contains(SettingsModel.TV_BRO_UA_PREFIX) == true) {
            config.userAgentString.value = null
        }
        setItems(buildItems())
    }

    private fun buildItems(): List<SettingsItem> = mutableListOf<SettingsItem>().apply {
        add(SettingsItem.Header(R.string.settings_section_browsing))
        add(searchEngineItem())
        add(homePageItem())
        add(userAgentItem())
        add(SettingsItem.Header(R.string.settings_section_startup))
        add(
            SettingsItem.Toggle(
                iconRes = R.drawable.ic_refresh_grey_900_24dp,
                titleRes = R.string.settings_restore_tabs,
                summaryRes = R.string.settings_restore_tabs_summary,
                isChecked = { config.restoreTabsOnStartup },
                onCheckedChange = { config.restoreTabsOnStartup = it }
            )
        )
    }

    private fun currentEngineIndex(): Int {
        val index = Config.SearchEnginesURLs.indexOf(config.searchEngineURL.value)
        return if (index == -1) engineTitles.lastIndex else index
    }

    private fun searchEngineItem() = SettingsItem.Choice(
        iconRes = R.drawable.ic_settings_search_24dp,
        titleRes = R.string.settings_search_engine,
        value = { engineTitles[currentEngineIndex()] },
        entries = { engineTitles },
        selectedIndex = { currentEngineIndex() },
        onSelected = { position ->
            if (position == engineTitles.lastIndex) {
                SettingsRowFactory.promptText(
                    context,
                    R.string.settings_search_engine,
                    config.searchEngineURL.value,
                    context.getString(R.string.search_engine_url_with_query_placeholder_query)
                ) { url ->
                    settingsModel.setSearchEngineURL(url)
                    rebuild()
                }
            } else {
                settingsModel.setSearchEngineURL(Config.SearchEnginesURLs[position])
                rebuild()
            }
        }
    )

    private fun homePageItem() = SettingsItem.Choice(
        iconRes = R.drawable.ic_home_grey_900_24dp,
        titleRes = R.string.home_page,
        value = { homePageValue() },
        entries = { context.resources.getStringArray(R.array.home_page_modes).toList() },
        selectedIndex = { settingsModel.homePageMode.ordinal },
        onSelected = { position ->
            val mode = Config.HomePageMode.entries[position]
            if (mode == Config.HomePageMode.CUSTOM) {
                SettingsRowFactory.promptText(
                    context,
                    R.string.custom_home_page_url,
                    settingsModel.homePage,
                    context.getString(R.string.url)
                ) { url ->
                    settingsModel.setHomePageProperties(Config.HomePageMode.CUSTOM, url)
                    rebuild()
                }
            } else {
                settingsModel.setHomePageProperties(
                    mode, settingsModel.homePage
                )
                rebuild()
            }
        }
    )

    private fun homePageValue(): CharSequence {
        val mode = settingsModel.homePageMode
        return if (mode == Config.HomePageMode.CUSTOM) settingsModel.homePage
        else context.resources.getStringArray(R.array.home_page_modes)[mode.ordinal]
    }

    private fun userAgentIndex(): Int {
        val value = config.userAgentString.value ?: return 0
        val index = settingsModel.uaStrings.indexOf(value)
        return if (index == -1) settingsModel.userAgentStringTitles.lastIndex else index
    }

    private fun userAgentItem() = SettingsItem.Choice(
        iconRes = R.drawable.ic_settings_person_24dp,
        titleRes = R.string.settings_user_agent,
        value = { settingsModel.userAgentStringTitles[userAgentIndex()] },
        entries = { settingsModel.userAgentStringTitles.toList() },
        selectedIndex = { userAgentIndex() },
        onSelected = { position ->
            if (position == settingsModel.userAgentStringTitles.lastIndex) {
                SettingsRowFactory.promptText(
                    context,
                    R.string.edit_user_agent_string,
                    config.userAgentString.value ?: "",
                    context.getString(R.string.edit_user_agent_string)
                ) { ua ->
                    config.userAgentString.value = ua.trim(' ').ifEmpty { null }
                    rebuild()
                }
            } else {
                config.userAgentString.value = settingsModel.uaStrings[position].ifEmpty { null }
                rebuild()
            }
        }
    )
}
