package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.model.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The suggestions of the address field (no views), from the browsing history: the rows of the list under
 * (TV) or above (phone) the field, and the address the field completes inline as the user types. The
 * history is loaded when the editing starts ([start]) and searched in memory, as the history screens do:
 * one entry per url (its visits counted), ranked by how the text matches it, then by visits and recency.
 */
class AddressSuggestions(
    private val history: Store,
    private val scope: CoroutineScope,
    private val host: Host,
    private val searchContext: CoroutineContext = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface Store {
        /** The whole history, the latest items first */
        suspend fun all(): List<HistoryItem>
    }

    interface Host {
        /** Draws the [rows] (none: the list is hidden) */
        fun render(rows: List<Row>)
    }

    /** A row of the list: the [words] of the text are marked in its title and address */
    data class Row(val url: String, val title: String, val address: String, val words: List<String>)

    /**
     * The address the field completes the typed text to: [text] starts with what was typed (its case kept),
     * [url] is what to open when the field is submitted with that text
     */
    data class InlineCompletion(val text: String, val url: String)

    /** A url of the history: its [visits] and [score] (visits weighed by recency) */
    class Entry(val url: String, val title: String, val visits: Int, val lastTime: Long, val score: Double) {
        /** The url without its scheme */
        val noScheme: String = withoutScheme(url)
        /** The url without its scheme and www. */
        val bare: String = noScheme.removeWwwPrefix()
    }

    private var entries: List<Entry> = emptyList()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var initialText = ""
    private var query = ""
    private var shown: List<Row> = emptyList()

    /** The editing starts with [initialText] in the field: nothing is suggested for it */
    fun start(initialText: String) {
        stop()
        this.initialText = initialText
        this.query = initialText
        loadJob = scope.launch {
            val items = history.all()
            entries = withContext(searchContext) { aggregate(items, clock()) }
            search(query)
        }
    }

    /** The editing ended: the history is not kept in memory */
    fun stop() {
        loadJob?.cancel()
        searchJob?.cancel()
        entries = emptyList()
        initialText = ""
        query = ""
        render(emptyList())
    }

    /** The text of the field changed: the rows follow it */
    fun query(text: String) {
        if (text == query) return
        query = text
        search(text)
    }

    /** The address the [typed] text completes to, null when nothing fits */
    fun inlineCompletion(typed: String): InlineCompletion? =
        if (typed == initialText) null else inlineCandidate(entries, typed)

    private fun search(text: String) {
        searchJob?.cancel()
        val entries = entries
        if (entries.isEmpty() || text == initialText || wordsOf(text).isEmpty()) {
            render(emptyList())
            return
        }
        searchJob = scope.launch {
            val rows = withContext(searchContext) { rank(entries, text) }
            if (text == query) render(rows)
        }
    }

    private fun render(rows: List<Row>) {
        if (rows == shown) return
        shown = rows
        host.render(rows)
    }

    companion object {
        const val MAX_ROWS = 6
        private const val DAY = 24 * 60 * 60 * 1000L
        private val SCHEMES = listOf("http://", "https://")

        fun wordsOf(text: String) = HistoryNavigator.wordsOf(text)

        fun withoutScheme(url: String) = HistoryNavigator.withoutScheme(url)

        private fun String.removeWwwPrefix() = if (startsWith("www.", ignoreCase = true)) substring(4) else this

        /** How much recent visits count over old ones */
        fun recencyWeight(lastTime: Long, now: Long): Double {
            val age = now - lastTime
            return when {
                age <= 4 * DAY -> 1.0
                age <= 14 * DAY -> 0.7
                age <= 31 * DAY -> 0.5
                age <= 90 * DAY -> 0.3
                else -> 0.1
            }
        }

        /**
         * One entry per url of the [items] (a trailing slash does not make another one): the latest url
         * and the latest title that is not blank, the visits counted
         */
        fun aggregate(items: List<HistoryItem>, now: Long): List<Entry> {
            class Group(var url: String, var title: String, var visits: Int, var lastTime: Long)
            val groups = LinkedHashMap<String, Group>()
            for (item in items) {
                if (item.url.isBlank()) continue
                val key = item.url.removeSuffix("/")
                val group = groups[key]
                if (group == null) {
                    groups[key] = Group(item.url, item.title, 1, item.time)
                    continue
                }
                group.visits++
                if (item.time > group.lastTime) {
                    group.url = item.url
                    group.lastTime = item.time
                    if (item.title.isNotBlank()) group.title = item.title
                } else if (group.title.isBlank()) {
                    group.title = item.title
                }
            }
            return groups.values.map {
                Entry(it.url, it.title, it.visits, it.lastTime, it.visits * recencyWeight(it.lastTime, now))
            }.sortedWith(compareByDescending<Entry> { it.score }.thenByDescending { it.lastTime })
        }

        /**
         * How the [text] matches the [entry], the better the lower; null when it does not:
         * 0 its site starts with the text, 1 its address starts with it, 2 each word is found and the first
         * one starts a word of the title or the address, 3 each word is found somewhere
         */
        fun matchClass(entry: Entry, text: String, words: List<String>): Int? {
            if (words.isEmpty()) return null
            if (words.size == 1) {
                val typed = words[0].stripSchemeAndWww()
                if (typed.isNotEmpty()) {
                    val site = entry.bare.substringBefore('/')
                    if (site.startsWith(typed, ignoreCase = true)) return 0
                    if (entry.bare.startsWith(typed, ignoreCase = true)) return 1
                }
            }
            val found = words.all { entry.title.contains(it, ignoreCase = true) || entry.url.contains(it, ignoreCase = true) }
            if (!found) return null
            val first = words[0]
            return if (startsAWord(entry.title, first) || startsAWord(entry.noScheme, first)) 2 else 3
        }

        private fun String.stripSchemeAndWww(): String {
            val scheme = SCHEMES.firstOrNull { startsWith(it, ignoreCase = true) }
            val rest = if (scheme != null) substring(scheme.length) else this
            return rest.removeWwwPrefix()
        }

        /** The [word] is in the [text] where a word of it starts */
        private fun startsAWord(text: String, word: String): Boolean {
            var start = text.indexOf(word, ignoreCase = true)
            while (start >= 0) {
                if (start == 0 || !text[start - 1].isLetterOrDigit()) return true
                start = text.indexOf(word, start + 1, ignoreCase = true)
            }
            return false
        }

        /** The rows for the [text]: the entries that match it, the best first, at most [MAX_ROWS] */
        fun rank(entries: List<Entry>, text: String): List<Row> {
            val words = wordsOf(text)
            if (words.isEmpty()) return emptyList()
            //the entries are sorted by score already, a stable sort keeps that order in each class
            return entries.asSequence()
                .mapNotNull { entry -> matchClass(entry, text, words)?.let { entry to it } }
                .sortedBy { it.second }
                .take(MAX_ROWS)
                .map { (entry, _) -> rowOf(entry, words) }
                .toList()
        }

        private fun rowOf(entry: Entry, words: List<String>): Row {
            val item = HistoryItem().also {
                it.url = entry.url
                it.title = entry.title
            }
            return Row(entry.url, entry.title.ifBlank { entry.noScheme }, HistoryNavigator.addressOf(item, words), words)
        }

        /**
         * The address the [typed] text completes to, from the best [entries] (sorted by score): while the
         * text is in the site part (no '/' typed yet), the site ("yo" → "youtube.com", opening the scheme
         * and the site of the best url of that site); after a '/', the url that starts with it. The text is
         * compared with the urls in the form it is typed in: with a scheme, with www., or without both.
         * Text with spaces is a search, never completed.
         */
        fun inlineCandidate(entries: List<Entry>, typed: String): InlineCompletion? {
            if (typed.isEmpty() || typed.any { it.isWhitespace() }) return null
            val withScheme = SCHEMES.any { typed.startsWith(it, ignoreCase = true) }
            val withWww = !withScheme && typed.startsWith("www.", ignoreCase = true)
            fun formOf(entry: Entry) = when {
                withScheme -> entry.url
                withWww -> entry.noScheme
                else -> entry.bare
            }
            //where the site ends in the typed text
            val siteStart = if (withScheme) typed.indexOf("://") + 3 else 0
            val inSite = typed.indexOf('/', siteStart) < 0
            if (inSite) {
                //the sites that start with the text, by the scores of all their urls
                class Site(val text: String, var score: Double, val best: Entry)
                val sites = LinkedHashMap<String, Site>()
                for (entry in entries) {
                    val form = formOf(entry)
                    if (!form.startsWith(typed, ignoreCase = true)) continue
                    val siteEnd = form.indexOf('/', siteStart).let { if (it < 0) form.length else it }
                    val site = form.substring(0, siteEnd)
                    if (site.length < typed.length) continue
                    val key = site.lowercase()
                    val known = sites[key]
                    if (known == null) sites[key] = Site(site, entry.score, entry) else known.score += entry.score
                }
                val best = sites.values.maxByOrNull { it.score } ?: return null
                val scheme = best.best.url.substringBefore("://")
                val url = scheme + "://" + best.best.noScheme.substringBefore('/') + "/"
                return InlineCompletion(typed + best.text.substring(typed.length), url)
            }
            val entry = entries.firstOrNull { formOf(it).startsWith(typed, ignoreCase = true) } ?: return null
            return InlineCompletion(typed + formOf(entry).substring(typed.length), entry.url)
        }
    }
}
