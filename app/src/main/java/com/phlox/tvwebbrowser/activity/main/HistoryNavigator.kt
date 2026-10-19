package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * What the history screens show and do, without their look: the TV screen over the window and the page
 * of the phone. The whole history is loaded when a screen opens and searched in memory: an item matches
 * when each word of the query is in its title or its url, ignoring case. A tap (OK) opens the page; the
 * selection mode (a long press, or Select of the options) picks items to delete. Changes go to
 * [Host.render], with the row (or the field) to focus when the focus has to move.
 */
class HistoryNavigator(
    private val context: Context,
    private val history: Store,
    private val scope: CoroutineScope,
    private val host: Host,
    private val searchDelay: Long = SEARCH_DELAY,
    private val searchContext: CoroutineContext = Dispatchers.Default
) {
    companion object {
        /** Typing in the field searches once it pauses */
        const val SEARCH_DELAY = 200L

        fun keyOf(id: Long) = "h$id"

        /** The words of the query, each to be found in the title or the url */
        fun wordsOf(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        fun matches(item: HistoryItem, words: List<String>): Boolean = words.all {
            item.title.contains(it, ignoreCase = true) || item.url.contains(it, ignoreCase = true)
        }

        /** Where the [words] are in the [text], sorted, overlapping ones merged */
        fun highlights(text: String, words: List<String>): List<IntRange> {
            val found = ArrayList<IntRange>()
            for (word in words) {
                var start = text.indexOf(word, ignoreCase = true)
                while (start >= 0) {
                    found += start until start + word.length
                    start = text.indexOf(word, start + word.length, ignoreCase = true)
                }
            }
            found.sortBy { it.first }
            val merged = ArrayList<IntRange>()
            for (range in found) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
                } else {
                    merged += range
                }
            }
            return merged
        }

        /** The host of the [url], or the url without its scheme if it has none */
        fun hostOf(url: String): String = try {
            Uri.parse(url).host?.ifEmpty { null }
        } catch (e: Exception) {
            null
        } ?: withoutScheme(url)

        fun withoutScheme(url: String) = url.substringAfter("://")

        /**
         * The address shown under the title: the host, or the whole url (without the scheme) when a word
         * of the search is found only in the rest of it, so the match can be seen
         */
        fun addressOf(item: HistoryItem, words: List<String>): String {
            val host = hostOf(item.url)
            val hidden = words.any {
                !item.title.contains(it, ignoreCase = true) && !host.contains(it, ignoreCase = true)
            }
            return if (hidden) withoutScheme(item.url) else host
        }
    }

    interface Store {
        /** The whole history, the latest items first */
        suspend fun all(): List<HistoryItem>
        suspend fun delete(items: List<HistoryItem>)
        suspend fun deleteAll()
    }

    interface Host {
        /** [focus] is null when the focus stays where it is */
        fun render(screen: Screen, focus: Focus?)
        /** Opens the page in the current tab (the screen closes) */
        fun open(url: String)
        fun openInNewTab(url: String)
        fun copyLink(url: String)
        fun showMessage(@DrawableRes icon: Int, message: String)
        /** Asks the [message], with a Delete button that runs the [action] */
        fun confirm(message: String, action: () -> Unit)
    }

    /** What the history screens need from their shell */
    interface ShellCallback {
        fun open(url: String)
        fun openInNewTab(url: String)
        fun showMessage(@DrawableRes icon: Int, message: String)
        fun confirm(message: String, action: () -> Unit)
        fun startVoiceSearch(onResult: (String) -> Unit)
    }

    /** What the options of a row do */
    enum class Action(@DrawableRes val icon: Int, @StringRes val label: Int) {
        OPEN_IN_NEW_TAB(R.drawable.ic_new_tab, R.string.open_in_new_tab),
        COPY_LINK(R.drawable.ic_link, R.string.copy_link),
        SELECT(R.drawable.ic_check_box, R.string.select),
        DELETE(R.drawable.ic_delete, R.string.delete)
    }

    /**
     * @param items the items shown: all of them, or those found, the latest first
     * @param words the words searched (none when not searching)
     * @param selection the ids of the items chosen in the selection mode, null when not in it
     * @param loaded false until the history is loaded
     * @param total the number of all the items
     */
    class Screen(
        val items: List<HistoryItem>,
        val query: String,
        val words: List<String>,
        val selection: Set<Long>?,
        val loaded: Boolean,
        val total: Int
    ) {
        val searching: Boolean get() = words.isNotEmpty()
        val selecting: Boolean get() = selection != null
        fun isSelected(item: HistoryItem) = selection?.contains(item.id) == true
    }

    /** Focus the row (or the field) of the [key], else the row at [index] */
    class Focus(val key: String?, val index: Int = 0)

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var loaded = false
    private var all: List<HistoryItem> = emptyList()
    private var shown: List<HistoryItem> = emptyList()
    private var query = ""
    private var words: List<String> = emptyList()
    private var selection: LinkedHashSet<Long>? = null

    val isSelecting: Boolean get() = selection != null
    val currentQuery: String get() = query

    /** Loads the history and shows it, the first row focused */
    fun start() {
        stop()
        render(null)
        loadJob = scope.launch {
            all = history.all()
            //the field may have been typed in meanwhile
            applySearch()
            loaded = true
            render(Focus(null))
        }
    }

    /** The screen closed: the history is not kept in memory */
    fun stop() {
        loadJob?.cancel()
        searchJob?.cancel()
        loaded = false
        all = emptyList()
        shown = emptyList()
        query = ""
        words = emptyList()
        selection = null
    }

    /** Searches the [text] once the typing pauses; the focus stays in the field */
    fun search(text: String) {
        if (text == query) return
        query = text
        if (wordsOf(text) == words) {
            render(null)
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(searchDelay)
            applySearch()
            render(null)
        }
    }

    /** Shows the items that match the words of the query */
    private suspend fun applySearch() {
        val newWords = wordsOf(query)
        val items = all
        shown = if (newWords.isEmpty()) items else withContext(searchContext) {
            items.filter { matches(it, newWords) }
        }
        words = newWords
    }

    fun onRowClick(item: HistoryItem) {
        if (selection != null) toggle(item) else host.open(item.url)
    }

    /** Starts the selection mode with the [item] chosen, or chooses it in that mode */
    fun onRowLongClick(item: HistoryItem) {
        if (selection == null) startSelection(item) else toggle(item)
    }

    fun actionsOf(@Suppress("UNUSED_PARAMETER") item: HistoryItem): List<Action> = Action.entries

    fun perform(item: HistoryItem, action: Action) {
        when (action) {
            Action.OPEN_IN_NEW_TAB -> host.openInNewTab(item.url)
            Action.COPY_LINK -> {
                host.copyLink(item.url)
                host.showMessage(R.drawable.ic_link, context.getString(R.string.link_copied))
            }
            Action.SELECT -> startSelection(item)
            Action.DELETE -> remove(listOf(item))
        }
    }

    fun startSelection(item: HistoryItem) {
        selection = linkedSetOf(item.id)
        render(Focus(keyOf(item.id)))
    }

    fun toggle(item: HistoryItem) {
        val selection = selection ?: return
        if (!selection.remove(item.id)) selection.add(item.id)
        render(null)
    }

    /** Chooses all the items shown, or none if all of them are chosen already */
    fun selectAll() {
        val selection = selection ?: return
        if (shown.all { it.id in selection }) selection.clear() else shown.mapTo(selection) { it.id }
        render(null)
    }

    fun endSelection() {
        if (selection == null) return
        selection = null
        render(null)
    }

    /** Asks to delete the chosen items, and deletes them */
    fun deleteSelected() {
        val ids = selection ?: return
        val chosen = all.filter { it.id in ids }
        if (chosen.isEmpty()) {
            host.showMessage(R.drawable.ic_history, context.getString(R.string.nothing_selected))
            return
        }
        host.confirm(context.resources.getQuantityString(R.plurals.delete_history_items, chosen.size, chosen.size)) {
            selection = null
            remove(chosen)
        }
    }

    /** Asks to delete the whole history, and deletes it */
    fun clearAll() {
        if (all.isEmpty()) {
            host.showMessage(R.drawable.ic_history, context.getString(R.string.history_is_empty))
            return
        }
        host.confirm(context.getString(R.string.msg_delete_history_all)) {
            searchJob?.cancel()
            all = emptyList()
            shown = emptyList()
            selection = null
            render(Focus(null))
            scope.launch { history.deleteAll() }
        }
    }

    /** Back: leaves the selection mode. @return false if there is nothing to leave */
    fun onBack(): Boolean {
        if (selection == null) return false
        endSelection()
        return true
    }

    /** Removes the [items], moving the focus to the row after the first of them (the one before at the end) */
    private fun remove(items: List<HistoryItem>) {
        val ids = items.mapTo(HashSet()) { it.id }
        val first = shown.indexOfFirst { it.id in ids }
        val next = if (first == -1) null else
            shown.drop(first).firstOrNull { it.id !in ids } ?: shown.take(first).lastOrNull { it.id !in ids }
        all = all.filter { it.id !in ids }
        shown = shown.filter { it.id !in ids }
        selection?.removeAll(ids)
        render(Focus(next?.let { keyOf(it.id) }, maxOf(first, 0)))
        scope.launch { history.delete(items) }
    }

    private fun render(focus: Focus?) {
        host.render(Screen(shown, query, words, selection?.toSet(), loaded, all.size), focus)
    }
}
