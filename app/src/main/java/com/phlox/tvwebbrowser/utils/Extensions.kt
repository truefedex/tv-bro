package com.phlox.tvwebbrowser.utils

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import java.util.*

val View.activity: Activity?
    get() {
        var ctx = context
        while (true) {
            if (!ContextWrapper::class.java.isInstance(ctx)) {
                return null
            }
            if (Activity::class.java.isInstance(ctx)) {
                return ctx as Activity
            }
            ctx = (ctx as ContextWrapper).baseContext
        }
    }

fun Calendar.sameDay(other: Calendar): Boolean {
    return this.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            this.get(Calendar.MONTH) == other.get(Calendar.MONTH) &&
            this.get(Calendar.DAY_OF_MONTH) == other.get(Calendar.DAY_OF_MONTH)
}