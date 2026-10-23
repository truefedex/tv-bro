package com.phlox.tvwebbrowser.webengine

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.common.R

/** The page both engines show instead of a page they could not load (the `pages/error.html` template) */
object LoadErrorPage {
    private val TAG: String = LoadErrorPage::class.java.simpleName
    const val TEMPLATE_FILE = "pages/error.html"

    @Volatile
    private var template: String? = null

    /** The page for [kind]: [url] is the address that failed, [errorCode] the error as the engine names it */
    fun create(context: Context, kind: LoadErrorKind, url: String?, errorCode: String): String {
        return ErrorPageTemplate.fill(template(context), mapOf(
            "dir" to ErrorPageTemplate.textDirection(context),
            "state" to kind.state,
            "title" to context.getString(kind.title),
            "message" to context.getString(kind.message),
            "hostnameLabel" to context.getString(R.string.hostname),
            "hostname" to (url?.toUri()?.host ?: ""),
            "errorCodeLabel" to context.getString(R.string.error_page_error_code),
            "errorCode" to errorCode,
            "url" to (url ?: ""),
            "retry" to context.getString(
                if (kind == LoadErrorKind.CRASHED) R.string.error_page_reload else R.string.error_page_try_again),
            "back" to context.getString(if (kind.leadsBack) R.string.back_to_safety else R.string.error_page_back),
            "homeUrl" to Config.HOME_PAGE_URL
        ))
    }

    private fun template(context: Context): String = template ?: try {
        context.assets.open(TEMPLATE_FILE).bufferedReader().use { it.readText() }.also { template = it }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open error page template", e)
        "{{title}}: {{errorCode}}"
    }
}
