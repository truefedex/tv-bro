package com.phlox.tvwebbrowser.webengine

import android.content.Context
import androidx.core.net.toUri
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.common.R

/**
 * The page both engines show in place of a page the site filter blocks ([EngineSiteFilter]): the load error page
 * (`pages/error.html`) in its "blocked" state, with Back and Ask a parent, a link of [EngineSiteFilter.ASK_SCHEME]
 * that the engine gives the app.
 */
object SiteBlockPage {
    fun create(context: Context, url: String, block: SiteBlock): String {
        val ask = EngineSiteFilter.askUrl(url)
        return ErrorPageTemplate.fill(LoadErrorPage.template(context), mapOf(
            "dir" to ErrorPageTemplate.textDirection(context),
            "state" to "blocked",
            "title" to context.getString(R.string.site_blocked_title),
            "message" to context.getString(R.string.site_blocked_message),
            "hostnameLabel" to context.getString(R.string.hostname),
            "hostname" to (url.toUri().host ?: ""),
            "errorCodeLabel" to context.getString(R.string.site_blocked_category),
            "errorCode" to context.getString(block.category.title),
            "url" to ask,
            "retry" to context.getString(R.string.site_blocked_ask),
            "back" to context.getString(R.string.error_page_back),
            "homeUrl" to Config.HOME_PAGE_URL
        ))
    }
}
