package com.phlox.tvwebbrowser.service.downloads

/**
 * Commands of the browser processes (the main and the incognito one) to [DownloadService],
 * which runs in its own process: [android.os.Message.what] values and the keys of their data.
 * The state of the downloads goes back only through the database.
 */
object DownloadCommands {
    /** Starts the download of the database row [KEY_ID] */
    const val START = 1
    /** Cancels the download [KEY_ID] and deletes its file */
    const val CANCEL = 2
    /** Stops the download [KEY_ID], keeping its file to resume it */
    const val PAUSE = 3
    /** Continues the paused download [KEY_ID] from the bytes it has */
    const val RESUME = 4

    const val KEY_ID = "id"
    const val KEY_COOKIES = "cookies"
    const val KEY_REFERER = "referer"
    /** Open the installer when the download completes */
    const val KEY_INSTALL = "install"
    /**
     * The read end of a pipe with the contents of the file, for the downloads the web engine
     * fetches itself (GeckoView responses, blobs): the engine cannot hand its stream to another process
     */
    const val KEY_STREAM = "stream"
}
