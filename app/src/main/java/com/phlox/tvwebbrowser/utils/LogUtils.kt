package com.phlox.tvwebbrowser.utils

object LogUtils {
    //send exception info to crashlytics in case crashlytics included in current build
    fun recordException(e: Throwable) {
        try {
            val clazz = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val method = clazz.getMethod("getInstance")
            val crashlytics = method.invoke(null)
            val clazz2 = crashlytics::class.java
            val method2 = clazz2.getMethod("recordException", Throwable::class.java)
            method2.invoke(crashlytics, e)
        } catch (ex: ClassNotFoundException) {
            //thats ok - not all builds include crashlytics
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}