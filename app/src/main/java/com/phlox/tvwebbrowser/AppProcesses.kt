package com.phlox.tvwebbrowser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The processes the app runs in, told apart by their names. Android creates the [Application] in each of
 * them, also in the processes of libraries: GeckoView runs its content, GPU, socket, media and other
 * processes as services of the app (`:tab_…`, `:gpu_…` and so on), and they need nothing of the app.
 */
object AppProcesses {
    private const val TAG = "AppProcesses"
    /** The process of `IncognitoModeMainActivity` (see the manifest) */
    const val INCOGNITO_SUFFIX = ":incognito"
    /** The process of `KidsProfileMainActivity`: the browser in the kids profile */
    const val KIDS_SUFFIX = ":kids"
    /** The process of `DownloadService` (see the manifest) */
    const val DOWNLOADS_SUFFIX = ":downloads"

    enum class Kind {
        /** The browser, normal, incognito or of the kids profile: everything is initialized */
        BROWSER,
        /** The download service: the database and the notification channel */
        DOWNLOADS,
        /** A process of a library (GeckoView): the app initializes nothing */
        OTHER
    }

    /**
     * Only a process named after the package with a suffix the app does not know is [Kind.OTHER]. A name
     * that could not be read (empty) or that is not of the package (unit tests) is taken for the browser,
     * so that the browser is never left uninitialized.
     */
    fun kindOf(processName: String, packageName: String): Kind {
        val prefix = "$packageName:"
        if (!processName.startsWith(prefix)) return Kind.BROWSER
        return when (":" + processName.substring(prefix.length)) {
            INCOGNITO_SUFFIX, KIDS_SUFFIX -> Kind.BROWSER
            DOWNLOADS_SUFFIX -> Kind.DOWNLOADS
            else -> Kind.OTHER
        }
    }

    fun currentKind(app: Application): Kind = kindOf(currentName(app), app.packageName)

    /** The process is the browser of the kids profile */
    fun isKids(processName: String, packageName: String): Boolean = processName == packageName + KIDS_SUFFIX

    fun isCurrentKids(app: Application): Boolean = isKids(currentName(app), app.packageName)

    /** The process of the app with the [suffix] runs */
    fun isRunning(context: Context, suffix: String): Boolean {
        val name = context.packageName + suffix
        return context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
            .any { it.processName == name }
    }

    /** Ends the process of the app with the [suffix] if it runs (not this one) */
    fun end(context: Context, suffix: String) {
        val name = context.packageName + suffix
        val processes = context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
        for (process in processes) {
            if (process.processName == name && process.pid != Process.myPid()) Process.killProcess(process.pid)
        }
    }

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
