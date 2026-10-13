package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity

class AppearanceSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SettingsPageView(context, attrs, defStyleAttr) {

    private val settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)
    private val config = AppContext.provideConfig()

    init {
        setItems(buildItems())
    }

    private fun buildItems(): List<SettingsItem> = mutableListOf<SettingsItem>().apply {
        add(SettingsItem.Header(R.string.settings_section_look_and_feel))
        add(
            SettingsItem.Choice(
                iconRes = R.drawable.ic_settings_palette_24dp,
                titleRes = R.string.theme,
                value = { context.resources.getStringArray(R.array.themes)[config.theme.value.ordinal] },
                entries = { context.resources.getStringArray(R.array.themes).toList() },
                selectedIndex = { config.theme.value.ordinal },
                onSelected = { position -> config.theme.value = Config.Theme.entries[position] }
            )
        )
        add(
            SettingsItem.Toggle(
                iconRes = R.drawable.ic_settings_dark_mode_24dp,
                titleRes = R.string.settings_algorithmic_darkening,
                summaryRes = R.string.settings_algorithmic_darkening_summary,
                isChecked = { config.webviewUseAlgorithmicDarkeningWithDarkUiMode },
                onCheckedChange = { config.webviewUseAlgorithmicDarkeningWithDarkUiMode = it }
            )
        )
        add(
            SettingsItem.Toggle(
                iconRes = R.drawable.ic_settings_toolbar_24dp,
                titleRes = R.string.settings_keep_toolbar_open,
                summaryRes = R.string.settings_keep_toolbar_open_summary,
                isChecked = { config.keepToolbarOpen },
                onCheckedChange = {
                    config.keepToolbarOpen = it
                    (activity as? MainActivity)?.applyToolbarOverlayStyle()
                }
            )
        )
        add(
            SettingsItem.Toggle(
                iconRes = R.drawable.ic_settings_tv_24dp,
                titleRes = R.string.toggle_keep_screen_on,
                isChecked = { settingsModel.keepScreenOn.value },
                onCheckedChange = { settingsModel.keepScreenOn.value = it }
            )
        )
    }
}
