package com.phlox.tvwebbrowser.webengine.gecko.delegates

import androidx.annotation.StringRes
import com.phlox.tvwebbrowser.common.R
import org.mozilla.geckoview.WebRequestError

/** What the error page says about a load error of GeckoView: [state] picks its glyph and buttons */
enum class LoadErrorKind(val state: String, @StringRes val title: Int, @StringRes val message: Int) {
    INSECURE("insecure", R.string.connection_isnt_secure, R.string.error_page_insecure_message),
    DANGEROUS("dangerous", R.string.error_page_dangerous_title, R.string.error_page_dangerous_message),
    CRASHED("crashed", R.string.error_page_crashed_title, R.string.error_page_crashed_message),
    NETWORK("network", R.string.error_page_network_title, R.string.error_page_network_message),
    GENERIC("generic", R.string.error_page_generic_title, R.string.error_page_generic_message);

    /** The page offers only going back, not trying the site again */
    val leadsBack: Boolean get() = this == INSECURE || this == DANGEROUS

    companion object {
        fun of(category: Int, code: Int): LoadErrorKind = when {
            category == WebRequestError.ERROR_CATEGORY_SECURITY -> INSECURE
            category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> DANGEROUS
            code == WebRequestError.ERROR_CONTENT_CRASHED -> CRASHED
            category == WebRequestError.ERROR_CATEGORY_NETWORK || category == WebRequestError.ERROR_CATEGORY_PROXY ||
                    code == WebRequestError.ERROR_OFFLINE -> NETWORK
            else -> GENERIC
        }
    }
}
