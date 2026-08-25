package com.phlox.tvwebbrowser.activity.player

import android.view.KeyEvent

/**
 * Set by a player activity when it gives up on a stream, so that the browser can
 * move on to the next candidate once it is resumed. A player activity cannot
 * report back directly because it is started with startActivity().
 */
object PlaybackFailure {
    @Volatile
    var last: String? = null
}

/**
 * TV remotes disagree on which key is "menu": some send SETTINGS, others MENU or
 * INFO. Accept all of the usual ones so the settings overlay is reachable
 * whatever the remote sends.
 */
object PlayerKeys {
    val SETTINGS = intArrayOf(
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_CAPTIONS,
        KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU,
        KeyEvent.KEYCODE_BUTTON_Y
    )
}
