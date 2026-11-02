package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.InlineCompletion
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.Row
import com.phlox.tvwebbrowser.model.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
class AddressSuggestionsTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val day = 24 * 60 * 60 * 1000L
    private var id = 0L

    private fun visit(url: String, title: String = "", daysAgo: Int = 0) = HistoryItem().apply {
        this.id = ++this@AddressSuggestionsTest.id
        this.url = url
        this.title = title
        time = now - daysAgo * day
    }

    /** The visits, the latest first, as the history gives them */
    private fun entries(vararg visits: HistoryItem) =
        AddressSuggestions.aggregate(visits.sortedByDescending { it.time }, now)

    private fun urls(rows: List<Row>) = rows.map { it.url }

    @Test
    fun visitsOfAUrlMakeOneEntryWithTheLatestTitle() {
        val entries = entries(
            visit("https://example.com/", "Old", daysAgo = 3),
            visit("https://example.com", "New", daysAgo = 1),
            visit("https://example.com/", "", daysAgo = 0),
            visit("https://other.com/", "Other", daysAgo = 2)
        )
        assertEquals(2, entries.size)
        val example = entries.first { it.url.contains("example") }
        assertEquals(3, example.visits)
        assertEquals("New", example.title)
        assertEquals("https://example.com/", example.url)
    }

    @Test
    fun aSiteThatStartsWithTheTextComesFirst() {
        val entries = entries(
            visit("https://news.com/youtube-story", "About YouTube"),
            visit("https://news.com/youtube-story", "About YouTube"),
            visit("https://news.com/youtube-story", "About YouTube"),
            visit("https://www.youtube.com/watch?v=1", "A video", daysAgo = 20)
        )
        assertEquals(listOf("https://www.youtube.com/watch?v=1", "https://news.com/youtube-story"),
            urls(AddressSuggestions.rank(entries, "you")))
    }

    @Test
    fun moreAndNewerVisitsRankHigherInTheSameClass() {
        val entries = entries(
            visit("https://a.example.com/old", daysAgo = 200),
            visit("https://a.example.com/old", daysAgo = 201),
            visit("https://a.example.com/new", daysAgo = 1),
            visit("https://a.example.com/often", daysAgo = 5),
            visit("https://a.example.com/often", daysAgo = 6),
            visit("https://a.example.com/often", daysAgo = 7)
        )
        assertEquals(listOf("https://a.example.com/often", "https://a.example.com/new", "https://a.example.com/old"),
            urls(AddressSuggestions.rank(entries, "a.ex")))
    }

    @Test
    fun aWordThatStartsAWordOfTheTitleRanksAboveOneFoundInside() {
        val entries = entries(
            visit("https://one.com/", "Superkotlin", daysAgo = 0),
            visit("https://one.com/", "Superkotlin", daysAgo = 0),
            visit("https://two.com/", "Kotlin docs", daysAgo = 30)
        )
        assertEquals(listOf("https://two.com/", "https://one.com/"), urls(AddressSuggestions.rank(entries, "kotlin")))
    }

    @Test
    fun eachWordIsFoundIgnoringCaseAlsoOutsideAscii() {
        val entries = entries(
            visit("https://uk.wikipedia.org/wiki/TV", "Телебачення — Вікіпедія"),
            visit("https://en.wikipedia.org/wiki/TV", "Television — Wikipedia")
        )
        assertEquals(listOf("https://uk.wikipedia.org/wiki/TV"), urls(AddressSuggestions.rank(entries, "вікі тел")))
        assertEquals(listOf("вікі", "тел"), AddressSuggestions.rank(entries, "вікі тел").single().words)
    }

    @Test
    fun theRowsAreLimited() {
        val visits = (1..10).map { visit("https://site$it.com/") }.toTypedArray()
        assertEquals(AddressSuggestions.MAX_ROWS, AddressSuggestions.rank(entries(*visits), "site").size)
        assertTrue(AddressSuggestions.rank(entries(*visits), "  ").isEmpty())
    }

    @Test
    fun theSiteIsCompletedWhileItIsTyped() {
        val entries = entries(
            visit("https://www.youtube.com/watch?v=1"),
            visit("https://www.youtube.com/watch?v=2"),
            visit("https://yoga.com/", daysAgo = 1)
        )
        assertEquals(InlineCompletion("youtube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "yo"))
        //the case of what was typed stays
        assertEquals(InlineCompletion("YOutube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "YO"))
        assertEquals(InlineCompletion("yoga.com", "https://yoga.com/"), AddressSuggestions.inlineCandidate(entries, "yog"))
    }

    @Test
    fun theTextIsComparedInTheFormItIsTypedIn() {
        val entries = entries(visit("https://www.youtube.com/watch?v=1"))
        assertEquals(InlineCompletion("www.youtube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "www.you"))
        assertEquals(InlineCompletion("https://www.youtube.com", "https://www.youtube.com/"),
            AddressSuggestions.inlineCandidate(entries, "https://www.y"))
        assertNull(AddressSuggestions.inlineCandidate(entries, "http://www.y"))
    }

    @Test
    fun afterASlashTheUrlIsCompleted() {
        val entries = entries(visit("https://www.youtube.com/watch?v=Ab1"), visit("https://www.youtube.com/feed", daysAgo = 3))
        assertEquals(InlineCompletion("youtube.com/watch?v=Ab1", "https://www.youtube.com/watch?v=Ab1"),
            AddressSuggestions.inlineCandidate(entries, "youtube.com/w"))
        assertEquals(InlineCompletion("youtube.com/feed", "https://www.youtube.com/feed"),
            AddressSuggestions.inlineCandidate(entries, "youtube.com/f"))
    }

    @Test
    fun aSiteOnlyOnHttpKeepsItsSchemeAndPort() {
        val entries = entries(visit("http://router.local:8080/admin"))
        assertEquals(InlineCompletion("router.local:8080", "http://router.local:8080/"),
            AddressSuggestions.inlineCandidate(entries, "rou"))
        //the whole site typed still opens with its own scheme
        assertEquals(InlineCompletion("router.local:8080", "http://router.local:8080/"),
            AddressSuggestions.inlineCandidate(entries, "router.local:8080"))
    }

    @Test
    fun textWithSpacesOrNotMatchingIsNotCompleted() {
        val entries = entries(visit("https://youtube.com/"))
        assertNull(AddressSuggestions.inlineCandidate(entries, "you tube"))
        assertNull(AddressSuggestions.inlineCandidate(entries, "tube"))
        assertNull(AddressSuggestions.inlineCandidate(entries, ""))
    }

    @Test
    fun theListFollowsTheTextOnceTheHistoryIsLoaded() {
        val rendered = ArrayList<List<Row>>()
        val history = listOf(visit("https://example.com/", "Example"), visit("https://other.com/", "Other"))
        val suggestions = AddressSuggestions(object : AddressSuggestions.Store {
            override suspend fun all() = history
        }, CoroutineScope(Dispatchers.Unconfined), object : AddressSuggestions.Host {
            override fun render(rows: List<Row>) { rendered += rows }
        }, searchContext = EmptyCoroutineContext, clock = { now })

        suggestions.start("https://example.com/")
        //nothing for the address the editing started with
        assertTrue(rendered.isEmpty())
        assertNull(suggestions.inlineCompletion("https://example.com/"))

        suggestions.query("ex")
        assertEquals(listOf("https://example.com/"), urls(rendered.last()))
        assertEquals(InlineCompletion("example.com", "https://example.com/"), suggestions.inlineCompletion("ex"))

        suggestions.query("")
        assertTrue(rendered.last().isEmpty())

        suggestions.query("oth")
        suggestions.stop()
        assertTrue(rendered.last().isEmpty())
        assertNull(suggestions.inlineCompletion("oth"))
    }
}
