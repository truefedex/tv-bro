package com.phlox.tvwebbrowser.webengine.gecko

import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.util.Log

private const val FEATURE_MODULE_NAME = "gecko"

/**
 * GeckoView passes Context.getPackageResourcePath() to Gecko as the APK that holds omni.ja
 * (and resource://android/ assets such as the bundled web extension). When this module is
 * delivered as a split APK (Play feature delivery), that path is base.apk, which has none of
 * them, and Gecko crashes on startup. This wrapper points Gecko to the gecko split APK instead.
 *
 * GeckoRuntime stores context.getApplicationContext(), so the wrapper returns itself there.
 */
private class GeckoSplitContext(base: Context, private val apkPath: String) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getPackageResourcePath(): String = apkPath

    // Before Android 12 ContextWrapper doesn't override these, and Context implements them
    // through getApplicationContext(), which would recurse infinitely
    override fun registerComponentCallbacks(callback: ComponentCallbacks) {
        baseContext.registerComponentCallbacks(callback)
    }

    override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
        baseContext.unregisterComponentCallbacks(callback)
    }
}

/**
 * Returns the context that should be passed to GeckoRuntime.create(): the application context
 * when the module is fused into the base APK (universal APK), or a wrapper around it when the
 * module is installed as a split.
 *
 * A module that is only emulated by SplitCompat (right after the download, before the app
 * restarts, or with bundletool --local-testing) is not supported: the linker namespace of the
 * process does not see its native libraries, so Gecko fails to load them.
 */
internal fun geckoRuntimeContext(context: Context): Context {
    val app = context.applicationContext
    val splitApkPath = findInstalledSplitApk(app)
    if (splitApkPath == null) return app
    Log.i(GeckoWebEngine.TAG, "Gecko is installed as a split APK: $splitApkPath")
    return GeckoSplitContext(app, splitApkPath)
}

private fun findInstalledSplitApk(context: Context): String? {
    val info = context.applicationInfo
    val index = info.splitNames?.indexOf(FEATURE_MODULE_NAME) ?: return null
    if (index < 0) return null
    return info.splitSourceDirs?.getOrNull(index)
}
