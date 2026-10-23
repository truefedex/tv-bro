package com.phlox.tvwebbrowser.webengine

import androidx.annotation.StringRes
import com.phlox.tvwebbrowser.common.R

/**
 * What the error page says about a page an engine could not load: [state] picks its glyph and buttons. Each
 * engine maps its own errors to it
 */
enum class LoadErrorKind(val state: String, @StringRes val title: Int, @StringRes val message: Int) {
    INSECURE("insecure", R.string.connection_isnt_secure, R.string.error_page_insecure_message),
    DANGEROUS("dangerous", R.string.error_page_dangerous_title, R.string.error_page_dangerous_message),
    CRASHED("crashed", R.string.error_page_crashed_title, R.string.error_page_crashed_message),
    NETWORK("network", R.string.error_page_network_title, R.string.error_page_network_message),
    GENERIC("generic", R.string.error_page_generic_title, R.string.error_page_generic_message);

    /** The page offers only going back, not trying the site again */
    val leadsBack: Boolean get() = this == INSECURE || this == DANGEROUS
}
