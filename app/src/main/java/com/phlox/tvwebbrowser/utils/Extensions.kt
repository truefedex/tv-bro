package com.phlox.tvwebbrowser.utils

import android.app.Activity
import android.content.ContextWrapper
import android.view.View

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