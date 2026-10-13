package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.dialogs.ShortcutDialog
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut

class ControlsSettingsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SettingsPageView(context, attrs, defStyleAttr) {

    private val config = AppContext.provideConfig()

    init {
        setItems(buildItems())
    }

    private fun buildItems(): List<SettingsItem> = mutableListOf<SettingsItem>().apply {
        add(SettingsItem.Header(R.string.settings_section_input))
        add(
            SettingsItem.Toggle(
                iconRes = R.drawable.ic_settings_gamepad_24dp,
                titleRes = R.string.navigate_with_joystick_axes,
                isChecked = { !config.disableMotionAxesDpadNavigation },
                onCheckedChange = { config.disableMotionAxesDpadNavigation = !it }
            )
        )
        add(SettingsItem.Header(R.string.settings_section_cursor))
        add(
            SettingsItem.Slider(
                iconRes = R.drawable.ic_settings_mouse_24dp,
                titleRes = R.string.cursor_max_speed,
                min = Config.CURSOR_PHYSICS_PERCENT_MIN,
                max = Config.CURSOR_PHYSICS_PERCENT_MAX,
                value = { config.cursorMaxSpeedPercent },
                valueText = { context.getString(R.string.cursor_physics_percent, it) },
                onValueChange = { config.cursorMaxSpeedPercent = it }
            )
        )
        add(
            SettingsItem.Slider(
                iconRes = R.drawable.ic_settings_mouse_24dp,
                titleRes = R.string.cursor_acceleration,
                min = Config.CURSOR_PHYSICS_PERCENT_MIN,
                max = Config.CURSOR_PHYSICS_PERCENT_MAX,
                value = { config.cursorAccelerationPercent },
                valueText = { context.getString(R.string.cursor_physics_percent, it) },
                onValueChange = { config.cursorAccelerationPercent = it }
            )
        )
        add(SettingsItem.Header(R.string.shortcuts))
        Shortcut.entries.forEach { shortcut ->
            add(
                SettingsItem.KeyBinding(
                    iconRes = R.drawable.ic_settings_keyboard_24dp,
                    titleRes = shortcut.titleResId,
                    value = { shortcutKeyLabel(shortcut) },
                    onClick = { showShortcutDialog(shortcut) }
                )
            )
        }
    }

    private fun shortcutKeyLabel(shortcut: Shortcut): CharSequence =
        if (shortcut.keyCode == 0) context.getString(R.string.not_set)
        else Shortcut.shortcutKeysToString(shortcut, context)

    private fun showShortcutDialog(shortcut: Shortcut) {
        val dialog = ShortcutDialog(context, shortcut)
        dialog.setOnDismissListener { post { rebuild() } }
        dialog.show()
    }
}
