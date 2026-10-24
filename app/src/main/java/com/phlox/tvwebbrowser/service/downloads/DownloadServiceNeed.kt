package com.phlox.tvwebbrowser.service.downloads

import androidx.annotation.MainThread

/**
 * Whether a browser activity keeps [DownloadService] bound: while it is started and a download is in
 * progress or a downloads screen is shown. The process of the service then does not run while there is
 * nothing to download. A row a dead process left in progress counts as a download in progress: the service
 * is bound, its start marks the row interrupted, and the service is let go.
 */
@MainThread
class DownloadServiceNeed(private val onChanged: (needed: Boolean) -> Unit) {
    var needed = false
        private set

    /** The activity is started */
    var started = false
        set(value) {
            field = value
            update()
        }

    /** A download of the database is in progress */
    var inProgress = false
        set(value) {
            field = value
            update()
        }

    private var screens = 0

    /** A downloads screen (or panel) was shown, or closed */
    fun onScreenShown(shown: Boolean) {
        screens = if (shown) screens + 1 else (screens - 1).coerceAtLeast(0)
        update()
    }

    private fun update() {
        val now = started && (inProgress || screens > 0)
        if (now == needed) return
        needed = now
        onChanged(now)
    }
}
