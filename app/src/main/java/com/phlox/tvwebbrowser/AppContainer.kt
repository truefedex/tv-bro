package com.phlox.tvwebbrowser

import com.phlox.tvwebbrowser.utils.SerialWriteQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-wide objects that live as long as the process.
 */
class AppContainer {
    /** For work that must not be cancelled when an activity or a model is destroyed */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tabs database and files writes */
    val tabWrites = SerialWriteQueue(appScope)
}
