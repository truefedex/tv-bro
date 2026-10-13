package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.R

class PlaybackSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SettingsPageView(context, attrs, defStyleAttr) {

    private val config = AppContext.provideConfig()

    init {
        setItems(buildItems())
    }

    private fun buildItems(): List<SettingsItem> = mutableListOf<SettingsItem>().apply {
        add(
            SettingsItem.Toggle(
                iconRes = R.drawable.ic_settings_play_24dp,
                titleRes = R.string.allow_autoplay_media,
                summaryRes = R.string.allow_autoplay_media_summary,
                isChecked = { config.allowAutoplayMedia },
                onCheckedChange = { config.allowAutoplayMedia = it }
            )
        )
        add(
            SettingsItem.Toggle(
                iconRes = R.drawable.ic_settings_fullscreen_24dp,
                titleRes = R.string.settings_auto_fullscreen,
                summaryRes = R.string.settings_auto_fullscreen_summary,
                isChecked = { config.autoNativeFullscreen },
                onCheckedChange = { config.autoNativeFullscreen = it }
            )
        )
    }
}
