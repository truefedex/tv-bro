package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.phlox.tvwebbrowser.webengine.SiteBlock
import com.phlox.tvwebbrowser.webengine.SiteBlockPage
import java.util.concurrent.ConcurrentHashMap

/**
 * The block pages of the site filter in GeckoView. A page the filter blocks is denied in `onLoadRequest`, and the
 * block page is loaded in its place as a `data:` url ([pageUrl]) whose fragment holds the address it blocked, which
 * the engine reports to the app ([blockedUrlOf]), as it does for the local home page; the fragment stays with the
 * page in the history and in the saved state of the tab. The background script also cancels the request of such a
 * page (a load `onLoadRequest` does not see: Back, a restored tab, a window), which leaves the page blank: [cancelled]
 * keeps those for a while, so the end of that load shows the block page.
 */
internal object GeckoSiteBlocks {
    private const val KEEP_MS = 30_000L
    private const val FRAGMENT = "#tvbro-blocked="
    private class Entry(val block: SiteBlock, val time: Long)
    private val cancelledPages = ConcurrentHashMap<String, Entry>()

    /** The background script cancelled the request of the page of [url] */
    fun cancelled(url: String, block: SiteBlock) {
        val now = SystemClock.elapsedRealtime()
        cancelledPages.entries.removeIf { now - it.value.time > KEEP_MS }
        cancelledPages[url] = Entry(block, now)
    }

    /** The block of the page of [url] whose request the background script cancelled; null if it did not */
    fun takeCancelled(url: String?): SiteBlock? {
        url ?: return null
        val entry = cancelledPages.remove(url) ?: return null
        return entry.block.takeIf { SystemClock.elapsedRealtime() - entry.time <= KEEP_MS }
    }

    /** The `data:` url of the block page of [url] */
    fun pageUrl(context: Context, url: String, block: SiteBlock): String {
        val html = SiteBlockPage.create(context, url, block)
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP) +
            FRAGMENT + Uri.encode(url)
    }

    /** The address a block page stands for; null for another url */
    fun blockedUrlOf(url: String): String? {
        if (!url.startsWith("data:")) return null
        val at = url.indexOf(FRAGMENT)
        if (at < 0) return null
        return Uri.decode(url.substring(at + FRAGMENT.length)).takeIf { it.isNotEmpty() }
    }
}
