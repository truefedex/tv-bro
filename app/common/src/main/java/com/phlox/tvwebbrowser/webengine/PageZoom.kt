package com.phlox.tvwebbrowser.webengine

/**
 * The zoom of pages: below 100% the layout of a page gets wider and the engine fits it into the screen, as the
 * zoom of a desktop browser does (`assets/page_zoom.js` writes the width of its viewport). The engines' own
 * zoom is a pinch, which stops where the page fits the screen, so a page made for the width of the device could
 * not get smaller. The zoom is a setting of the host, which the scripts of pages ask
 * [WebEngineFactory.pageZooms] for when they start.
 */
object PageZoom {
    const val DEFAULT = 100
    /** The zooms a step goes through, from the smallest */
    val STEPS = listOf(33, 50, 67, 80, 90, DEFAULT)

    /** The next smaller zoom; null at the smallest */
    fun smaller(zoom: Int): Int? = STEPS.lastOrNull { it < zoom }

    /** The next bigger zoom, up to [DEFAULT]; null at it */
    fun bigger(zoom: Int): Int? = STEPS.firstOrNull { it > zoom }
}

/** The page zooms of hosts, set by the app in [WebEngineFactory.pageZooms]; asked on the threads of the engines */
fun interface EnginePageZooms {
    /** The zoom of the page of the [url] in percent, [PageZoom.DEFAULT] for most */
    fun zoomOf(url: String): Int
}

/** A [WebEngine] that can change the zoom of the page it shows (see [PageZoom]) */
interface PageZoomSupport {
    /** Gives the page shown the zoom at once, without a reload ([EnginePageZooms] gives it to the next pages) */
    fun applyPageZoom(zoom: Int)

    /** Tells (on the main thread) whether the user zoomed in past the width of the page, as a pinch does */
    fun isZoomedIn(callback: (Boolean) -> Unit)

    /**
     * Zooms the page in or out as a pinch does ([WebEngine.zoomIn], [WebEngine.zoomOut]) and tells (on the main
     * thread) the zoom it has then relative to the width of the page (1 where it fits the screen); null if the
     * page does not tell or the engine did not zoom (another pinch was in progress)
     */
    fun zoomVisually(zoomIn: Boolean, onDone: (Double?) -> Unit)
}

/** Null if the engine can not change the zoom of pages */
val WebEngine.pageZoom: PageZoomSupport?
    get() = this as? PageZoomSupport
