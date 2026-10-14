package com.phlox.tvwebbrowser.browser

import android.util.Log
import android.webkit.URLUtil
import androidx.core.net.toUri

/**
 * Decides what to do with a url the page (or the app) is about to load.
 * Performs no actions itself: the caller handles the returned [UrlRoute].
 */
object UrlRouter {
    private const val TAG = "UrlRouter"

    enum class UrlRoute {
        /** Do not load the url */
        BLOCK,
        /** Let the web engine load the url */
        LOAD,
        /** Do not load the url, ask for the local network access permission first */
        REQUEST_LOCAL_NETWORK_PERMISSION,
        /** "intent:" url, should be opened with [android.content.Intent.parseUri] */
        OPEN_INTENT_URI,
        /** Url with a non-network scheme that some external app may handle */
        OPEN_IN_EXTERNAL_APP
    }

    /**
     * @param needsLocalNetworkPermission called only for urls with a scheme, before the scheme checks;
     * should return true if the url points to the local network and the app has no permission to access it
     */
    fun route(url: String, needsLocalNetworkPermission: (String) -> Boolean): UrlRoute {
        val uri = try {
            url.toUri()
        } catch (e: Exception) {
            Log.e(TAG, "route: ", e)
            return UrlRoute.BLOCK
        }

        if (uri.scheme == null) {
            Log.d(TAG, "route: no scheme: $url")
            return UrlRoute.BLOCK
        }

        if (needsLocalNetworkPermission(url)) {
            return UrlRoute.REQUEST_LOCAL_NETWORK_PERMISSION
        }

        if (URLUtil.isNetworkUrl(url) || uri.scheme.equals("javascript", true) ||
                uri.scheme.equals("data", true) || uri.scheme.equals("about", true) ||
                uri.scheme.equals("blob", true)) {
            Log.d(TAG, "route: network url: $url")
            return UrlRoute.LOAD
        }

        if (uri.scheme.equals("intent", true)) {
            Log.d(TAG, "route: intent url: $url")
            return UrlRoute.OPEN_INTENT_URI
        }

        Log.d(TAG, "route: non-network url: $url")
        return UrlRoute.OPEN_IN_EXTERNAL_APP
    }
}
