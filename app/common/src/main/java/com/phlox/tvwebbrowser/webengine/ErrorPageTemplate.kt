package com.phlox.tvwebbrowser.webengine

import android.content.Context
import android.text.TextUtils
import android.view.View

/**
 * The error pages the engines show (their templates are HTML files in the assets): the app fills the holes of a
 * template (`{{name}}`) before the page is loaded, so the pages need no bridge to the app to show the error
 */
object ErrorPageTemplate {
    //both braces escaped: the regular expressions of Android (ICU) refuse an unescaped "}"
    private val HOLE = Regex("""\{\{(\w+)\}\}""")

    /** Puts the values into the holes of [template], escaped for HTML; a hole without a value is left empty */
    fun fill(template: String, values: Map<String, String>): String =
        HOLE.replace(template) { escapeHtml(values[it.groupValues[1]] ?: "") }

    fun escapeHtml(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** The `dir` of the page: `rtl` for the languages written from right to left */
    fun textDirection(context: Context): String {
        val locale = context.resources.configuration.locales[0]
        return if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
    }
}
