package com.phlox.tvwebbrowser.webengine.gecko.delegates

import com.phlox.tvwebbrowser.webengine.LoadErrorKind
import org.mozilla.geckoview.WebRequestError

/** The load errors of GeckoView as the error page shows them */
object GeckoLoadErrors {
    fun kind(category: Int, code: Int): LoadErrorKind = when {
        category == WebRequestError.ERROR_CATEGORY_SECURITY -> LoadErrorKind.INSECURE
        category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> LoadErrorKind.DANGEROUS
        code == WebRequestError.ERROR_CONTENT_CRASHED -> LoadErrorKind.CRASHED
        category == WebRequestError.ERROR_CATEGORY_NETWORK || category == WebRequestError.ERROR_CATEGORY_PROXY ||
                code == WebRequestError.ERROR_OFFLINE -> LoadErrorKind.NETWORK
        else -> LoadErrorKind.GENERIC
    }
}
