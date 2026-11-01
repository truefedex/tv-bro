package com.phlox.tvwebbrowser.activity.main

import android.content.res.Resources
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.webengine.FindInPageSupport
import com.phlox.tvwebbrowser.webengine.FindResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Find in page (no views): the search of text in the page of the current tab, which the find bars of the
 * shells draw ([Host.render]) and drive. Each change of the text searches again (as the user types). A search
 * belongs to the page of its tab: it ends when another tab is shown ([onPage]), and runs again when the page
 * changes (another address, or a load finished), since the matches are those of the document. The last text
 * is kept for the next opening.
 */
class FindInPageController(private val scope: CoroutineScope, private val host: Host) {
    interface Host {
        /** The engine of the current tab; null without a tab or when it can not find text */
        fun currentEngine(): FindInPageSupport?
        /** Draws the [state]; null: the find bar is closed */
        fun render(state: State?)
    }

    data class State(
        val query: String,
        /** The matches of [query]; null while they are not known (nothing searched, the page loads) */
        val result: FindResult? = null,
    ) {
        /** There are matches to go through */
        val hasMatches: Boolean get() = result != null && result.total != 0

        /** "3/17", null while the matches are not known */
        fun countText(resources: Resources): String? {
            val result = result ?: return null
            return if (result.total == FindResult.MANY) {
                resources.getString(R.string.find_in_page_count_many, result.current, MANY_COUNT)
            } else {
                resources.getString(R.string.find_in_page_count, result.current, result.total)
            }
        }
    }

    var state: State? = null
        private set
    val isOpen: Boolean get() = state != null
    /** The text of the last search, offered when the bar opens again */
    var lastQuery = ""
        private set
    private var engine: FindInPageSupport? = null
    private var resultJob: Job? = null
    private var tabIdentity: Int? = null
    private var page: String? = null
    private var loading = false

    /** Opens the bar for the current page; false when its engine can not find text */
    fun open(): Boolean {
        if (state != null) return true
        val engine = host.currentEngine() ?: return false
        this.engine = engine
        resultJob = scope.launch { engine.findResult.collect(::onResult) }
        setState(State(lastQuery))
        if (lastQuery.isNotEmpty() && !loading) engine.find(lastQuery)
        return true
    }

    /** The text of the field changed */
    fun setQuery(text: String) {
        val state = state ?: return
        if (text == state.query) return
        lastQuery = text
        setState(State(text))
        val engine = engine ?: return
        if (text.isEmpty()) engine.clearFind() else engine.find(text)
    }

    fun next() = step(forward = true)

    fun previous() = step(forward = false)

    private fun step(forward: Boolean) {
        if (state?.hasMatches != true) return
        engine?.findNext(forward)
    }

    /** Closes the bar: the highlights go */
    fun close() {
        if (state == null) return
        resultJob?.cancel()
        resultJob = null
        engine?.clearFind()
        engine = null
        setState(null)
    }

    /** The shown page ([com.phlox.tvwebbrowser.browser.CurrentTabState]) */
    fun onPage(tabIdentity: Int, url: String, loading: Boolean) {
        val tabChanged = this.tabIdentity != null && tabIdentity != this.tabIdentity
        val page = url.substringBefore('#')
        val pageChanged = this.page != null && page != this.page
        val started = loading && !this.loading
        val finished = !loading && this.loading
        this.tabIdentity = tabIdentity
        this.page = page
        this.loading = loading
        val state = state ?: return
        if (tabChanged) {
            close()
            return
        }
        if (state.query.isEmpty()) return
        if (loading) {
            //the matches come when the page has loaded
            if (started || pageChanged) setState(state.copy(result = null))
        } else if (finished || pageChanged) {
            engine?.find(state.query)
        }
    }

    private fun onResult(result: FindResult?) {
        val state = state ?: return
        if (result == null || result.query != state.query) return
        setState(state.copy(result = result))
    }

    private fun setState(state: State?) {
        if (state == this.state) return
        this.state = state
        host.render(state)
    }

    companion object {
        /** GeckoView counts up to 1000 matches (accessibility.typeaheadfind.matchesCountLimit) */
        const val MANY_COUNT = 1000
    }
}
