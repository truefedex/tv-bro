package com.phlox.tvwebbrowser.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import java.util.*
import kotlin.collections.ArrayList

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

val ViewGroup.childs: ArrayList<View>
    get() {
        val result = ArrayList<View>()
        for (i in 0 until this.childCount) {
            result.add(this.getChildAt(i))
        }
        return result
    }

fun Int.dip2px(context: Context): Float {
    return (this * context.resources.displayMetrics.density)
}