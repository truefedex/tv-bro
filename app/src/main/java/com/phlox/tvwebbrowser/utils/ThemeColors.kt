package com.phlox.tvwebbrowser.utils

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/**
 * The color of a theme attribute (a color token of `Theme.TVBro`, see `attrs.xml`).
 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    check(theme.resolveAttribute(attr, value, true)) { "The theme has no color for the attribute $attr" }
    return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
}
