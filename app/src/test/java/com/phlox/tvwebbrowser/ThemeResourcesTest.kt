package com.phlox.tvwebbrowser

import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.utils.themeColor
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Drawables and colors of the app refer to theme attributes, so each of them has to load with the app theme */
@RunWith(RobolectricTestRunner::class)
class ThemeResourcesTest {
    private val context = ContextThemeWrapper(AppContext.get(), R.style.AppTheme)

    @Test
    fun allDrawablesLoadWithTheAppTheme() {
        val failed = resourceIds(R.drawable::class.java).mapNotNull { (name, id) ->
            runCatching { assertNotNull(ContextCompat.getDrawable(context, id)) }
                .exceptionOrNull()?.let { "$name: ${rootMessage(it)}" }
        }
        if (failed.isNotEmpty()) fail(failed.joinToString("\n"))
    }

    @Test
    fun allColorsLoadWithTheAppTheme() {
        val failed = resourceIds(R.color::class.java).mapNotNull { (name, id) ->
            runCatching { assertNotNull(ContextCompat.getColorStateList(context, id)) }
                .exceptionOrNull()?.let { "$name: ${rootMessage(it)}" }
        }
        if (failed.isNotEmpty()) fail(failed.joinToString("\n"))
    }

    @Test
    fun allColorTokensAreSetByTheAppTheme() {
        val failed = resourceIds(R.attr::class.java).filter { it.first.startsWith("tbColor") }.mapNotNull { (name, id) ->
            runCatching { context.themeColor(id) }.exceptionOrNull()?.let { name }
        }
        if (failed.isNotEmpty()) fail("Not set by the theme: $failed")
    }

    private fun resourceIds(rClass: Class<*>) =
        rClass.fields.filter { it.type == Int::class.javaPrimitiveType }.map { it.name to it.getInt(null) }

    private fun rootMessage(e: Throwable): String {
        var cause = e
        while (cause.cause != null) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
