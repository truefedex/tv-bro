package com.phlox.tvwebbrowser.activity.main

/**
 * What the browser shows, one at a time, in the one process of the app: another space takes the screen in a new
 * browser activity (`MainActivity.switchSpace`). The engines keep the pages data of the spaces apart (profiles of
 * WebView, private sessions and a container of GeckoView).
 */
enum class BrowserSpace {
    /** The main profile */
    MAIN,
    /** The incognito mode of the main profile: its data are deleted when it is left */
    INCOGNITO,
    /** The kids profile: its own pages data, database and tab files, which stay */
    KIDS
}
