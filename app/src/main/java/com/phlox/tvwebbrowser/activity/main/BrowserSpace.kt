package com.phlox.tvwebbrowser.activity.main

/**
 * Where the browser runs. Each space has a browser activity in a process of its own (see the manifest),
 * because the engines keep the data of pages per process; the processes replace each other, so only one
 * shows the browser at a time.
 */
enum class BrowserSpace {
    /** The main profile */
    MAIN,
    /** The incognito mode of the main profile: its data are deleted when it is left */
    INCOGNITO,
    /** The kids profile: its own pages data, database and tab files, which stay */
    KIDS
}
