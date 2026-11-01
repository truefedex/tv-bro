package com.phlox.tvwebbrowser.webengine

import kotlinx.coroutines.flow.StateFlow

/**
 * A [WebEngine] that finds text in its page (Find in page): it highlights all matches, ignoring case, and
 * scrolls to the current one.
 */
interface FindInPageSupport {
    /** The result of the last search; null while there is none. The same flow for the engine's whole life */
    val findResult: StateFlow<FindResult?>
    /** Highlights the matches of [text] and goes to the first one; an empty [text] ends the search ([clearFind]) */
    fun find(text: String)
    /** Goes to the next match (the previous one when [forward] is false), round from the last to the first */
    fun findNext(forward: Boolean)
    /** Removes the highlights and forgets the search */
    fun clearFind()
}

data class FindResult(
    /** The text that was searched */
    val query: String,
    /** The current match, from 1; 0 when there is none */
    val current: Int,
    /** The number of matches; [MANY] when there are more than the engine counts */
    val total: Int,
) {
    companion object {
        const val MANY = -1
    }
}

/** Null if the engine can not find text in its page */
val WebEngine.findInPage: FindInPageSupport?
    get() = this as? FindInPageSupport
