package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Declarative description of a single row in a settings page. [SettingsPageView] turns these into
 * the native TV-settings-style rows (icon + title/summary + trailing control) and rebuilds them
 * whenever visibility changes.
 */
sealed interface SettingsItem {
    /** Whether the row should be shown at all. Evaluated on every rebuild. */
    fun isVisible(): Boolean = true

    /** Non-focusable dimmed group title. */
    data class Header(
        @param:StringRes val titleRes: Int,
        val visible: () -> Boolean = { true }
    ) : SettingsItem {
        override fun isVisible() = visible()
    }

    /** Row with a trailing switch. The whole row is the click target, like on Android TV. */
    data class Toggle(
        @param:DrawableRes val iconRes: Int,
        @param:StringRes val titleRes: Int,
        @param:StringRes val summaryRes: Int? = null,
        val isChecked: () -> Boolean,
        val onCheckedChange: (Boolean) -> Unit,
        val visible: () -> Boolean = { true }
    ) : SettingsItem {
        override fun isVisible() = visible()
    }

    /** Row showing the current value and a chevron; OK opens a single-choice dialog. */
    data class Choice(
        @param:DrawableRes val iconRes: Int,
        @param:StringRes val titleRes: Int,
        @param:StringRes val summaryRes: Int? = null,
        val value: () -> CharSequence,
        val entries: () -> List<CharSequence>,
        val selectedIndex: () -> Int,
        val onSelected: (Int) -> Unit,
        val visible: () -> Boolean = { true }
    ) : SettingsItem {
        override fun isVisible() = visible()
    }

    /** Row showing the current value and a chevron; OK opens a text input dialog. */
    data class TextInput(
        @param:DrawableRes val iconRes: Int,
        @param:StringRes val titleRes: Int,
        @param:StringRes val summaryRes: Int? = null,
        val value: () -> CharSequence,
        val hint: () -> CharSequence,
        val onCommit: (String) -> Unit,
        val visible: () -> Boolean = { true }
    ) : SettingsItem {
        override fun isVisible() = visible()
    }

    /** Row that runs an action on OK, optionally showing a progress spinner. */
    data class Action(
        @param:DrawableRes val iconRes: Int,
        @param:StringRes val titleRes: Int,
        @param:StringRes val summaryRes: Int? = null,
        val onClick: () -> Unit,
        val isInProgress: () -> Boolean = { false },
        val visible: () -> Boolean = { true }
    ) : SettingsItem {
        override fun isVisible() = visible()
    }

    /** Row with an inline slider adjusted with the D-pad left/right. */
    data class Slider(
        @param:DrawableRes val iconRes: Int,
        @param:StringRes val titleRes: Int,
        val min: Int,
        val max: Int,
        val value: () -> Int,
        val valueText: (Int) -> CharSequence,
        val onValueChange: (Int) -> Unit
    ) : SettingsItem

    /** Row showing a bound key and a chevron; OK opens the key-capture dialog. */
    data class KeyBinding(
        @param:DrawableRes val iconRes: Int,
        @param:StringRes val titleRes: Int,
        val value: () -> CharSequence,
        val onClick: () -> Unit
    ) : SettingsItem

    /** Non-focusable read-only row (e.g. "Last update"). */
    data class Info(
        @param:DrawableRes val iconRes: Int? = null,
        @param:StringRes val titleRes: Int,
        val value: () -> CharSequence,
        val visible: () -> Boolean = { true }
    ) : SettingsItem {
        override fun isVisible() = visible()
    }
}
