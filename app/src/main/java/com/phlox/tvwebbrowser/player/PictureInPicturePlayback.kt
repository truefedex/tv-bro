package com.phlox.tvwebbrowser.player

import android.app.Activity
import com.phlox.tvwebbrowser.webengine.MediaCommand
import java.lang.ref.WeakReference

/**
 * The built-in player while it plays in a picture-in-picture window (`PictureInPictureActivity`), at most one per
 * process. The screen of the player hands its [NativePlayer] over without releasing it ([start]), the window shows
 * it; the player goes back to the screen when the window is expanded ([takeBack]), and is released when the window
 * is closed or another video opens in the built-in player ([end]). Main thread only.
 */
object PictureInPicturePlayback {
    /** The player in the window; null while there is none */
    var player: NativePlayer? = null
        private set
    private var window: WeakReference<Activity>? = null

    val isActive: Boolean get() = player != null

    /** [player] goes to the window; a window shown before ends */
    fun start(player: NativePlayer) {
        end()
        this.player = player
    }

    /** The window that shows the player: it is finished when the player goes back or the playback ends */
    fun attach(activity: Activity) {
        window = WeakReference(activity)
    }

    /** The player for the screen of the browser, still playing; the window is finished. Null if there is none */
    fun takeBack(): NativePlayer? {
        val player = player ?: return null
        this.player = null
        finishWindow()
        return player
    }

    /** Releases the player in the window, if it is [player] (any one when null), and finishes the window */
    fun end(player: NativePlayer? = null) {
        val current = this.player ?: return
        if (player != null && player !== current) return
        this.player = null
        current.release()
        finishWindow()
    }

    /** The media keys of the remote while the window is shown; false if there is no window */
    fun mediaCommand(command: MediaCommand): Boolean {
        val player = player ?: return false
        player.mediaCommand(command)
        return true
    }

    private fun finishWindow() {
        window?.get()?.takeUnless { it.isFinishing }?.finish()
        window = null
    }
}
