package com.phlox.tvwebbrowser

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The app runs in one process, but Android creates the [Application] also in the processes of libraries,
 * told apart by their names: GeckoView runs its content, GPU, socket, media and other
 * processes as services of the app (`:tab_…`, `:gpu_…` and so on), and they need nothing of the app.
 */
object AppProcesses {
    private const val TAG = "AppProcesses"

    enum class Kind {
        /** The process of the app (the browser, the downloads, the home screen channels): everything is initialized */
        APP,
        /** A process of a library (GeckoView): the app initializes nothing */
        OTHER
    }

    /**
     * Only a process named after the package with a suffix is [Kind.OTHER]: the app runs in one process. A name
     * that could not be read (empty) or that is not of the package (unit tests) is taken for the app, so that
     * the app is never left uninitialized.
     */
    fun kindOf(processName: String, packageName: String): Kind =
        if (processName.startsWith("$packageName:")) Kind.OTHER else Kind.APP

    fun currentKind(app: Application): Kind = kindOf(currentName(app), app.packageName)

    /** The name of this process; "" if it can not be read */
    private fun currentName(app: Application): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName() ?: ""
        return try {
            File("/proc/self/cmdline").readText().trimEnd(Char(0))
        } catch (e: IOException) {
            Log.e(TAG, "Can not read the process name of ${app.packageName}", e)
            ""
        }
    }
}
