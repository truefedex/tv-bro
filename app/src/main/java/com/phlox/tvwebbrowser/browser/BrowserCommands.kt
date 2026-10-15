package com.phlox.tvwebbrowser.browser

/**
 * Browser actions that the user can trigger from anywhere: hotkeys, buttons, voice.
 * The UI shell implements them (the TV activity now; a phone shell can do it in its own way).
 */
interface BrowserCommands {
    /** Back in the history of the current tab (the shell decides what to do when there is none) */
    fun goBack()
    fun goForward()
    fun goHome()
    fun reload()
    fun startVoiceSearch()
    fun togglePlayback()
    fun stopPlayback()
    fun rewind()
    fun fastForward()
}
