package com.phlox.tvwebbrowser.webengine

/**
 * A stream observed on the page that can be handed to a native player without
 * going through the page's own controls. Engine-agnostic so the toolbar can list
 * candidates without knowing whether the page is rendered by WebView or GeckoView.
 */
data class MediaCandidate(
    val url: String,
    val kind: Kind
) {
    enum class Kind { HLS, DASH, PROGRESSIVE }
}
