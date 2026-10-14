package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.model.HostConfig

object PopupBlockPolicy {
    /**
     * @param popupBlockLevel one of HostConfig.POPUP_BLOCK_* values, null if not set for the host
     * @param dialog true for js dialogs, false for new windows (tabs)
     */
    fun shouldBlockNewWindow(popupBlockLevel: Int?, dialog: Boolean, userGesture: Boolean): Boolean {
        val level = popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE
        return when (level) {
            HostConfig.POPUP_BLOCK_NONE -> false
            HostConfig.POPUP_BLOCK_DIALOGS -> dialog
            HostConfig.POPUP_BLOCK_NEW_AUTO_OPENED_TABS -> dialog || !userGesture
            else -> true
        }
    }
}
