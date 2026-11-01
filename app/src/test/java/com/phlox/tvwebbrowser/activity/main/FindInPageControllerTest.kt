package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.webengine.FindInPageSupport
import com.phlox.tvwebbrowser.webengine.FindResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FindInPageControllerTest {
    private class FakeFinder : FindInPageSupport {
        val calls = mutableListOf<String>()
        override val findResult = MutableStateFlow<FindResult?>(null)
        override fun find(text: String) { calls += "find $text" }
        override fun findNext(forward: Boolean) { calls += if (forward) "next" else "previous" }
        override fun clearFind() {
            calls += "clear"
            findResult.value = null
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var engine: FakeFinder? = FakeFinder()
    private val rendered = mutableListOf<FindInPageController.State?>()
    private val find = FindInPageController(scope, object : FindInPageController.Host {
        override fun currentEngine() = engine
        override fun render(state: FindInPageController.State?) { rendered += state }
    })
    private val calls get() = engine!!.calls

    @After
    fun tearDown() = scope.cancel()

    private fun report(query: String, current: Int, total: Int) {
        engine!!.findResult.value = FindResult(query, current, total)
    }

    @Test
    fun doesNotOpenWhereTheEngineCanNotFind() {
        engine = null
        assertFalse(find.open())
        assertFalse(find.isOpen)
        assertEquals(emptyList<FindInPageController.State?>(), rendered)
    }

    @Test
    fun typingSearchesAndShowsTheMatchesOfTheTextOnly() {
        assertTrue(find.open())
        assertEquals(FindInPageController.State(""), find.state)
        find.setQuery("ca")
        find.setQuery("cat")
        assertEquals(listOf("find ca", "find cat"), calls)
        //the matches of an older text come late
        report("ca", 1, 9)
        assertNull(find.state!!.result)
        report("cat", 1, 3)
        assertEquals(FindResult("cat", 1, 3), find.state!!.result)
        assertTrue(find.state!!.hasMatches)
        //an empty text ends the search
        find.setQuery("")
        assertEquals("clear", calls.last())
        assertEquals(FindInPageController.State(""), find.state)
    }

    @Test
    fun goesThroughMatchesOnlyWhenThereAreSome() {
        find.open()
        find.setQuery("dog")
        find.next()
        report("dog", 0, 0)
        assertFalse(find.state!!.hasMatches)
        find.next()
        find.previous()
        assertEquals(listOf("find dog"), calls)
        find.setQuery("cat")
        report("cat", 1, 2)
        find.next()
        find.previous()
        assertEquals(listOf("find dog", "find cat", "next", "previous"), calls)
    }

    @Test
    fun closingClearsTheHighlightsAndKeepsTheTextForTheNextTime() {
        find.onPage(1, "https://example.com/", loading = false)
        find.open()
        find.setQuery("cat")
        report("cat", 1, 3)
        find.close()
        assertFalse(find.isOpen)
        assertNull(rendered.last())
        assertEquals(listOf("find cat", "clear"), calls)
        //the result of a closed search is not shown
        report("cat", 2, 3)
        assertEquals(1, rendered.count { it == null })

        find.open()
        assertEquals("cat", find.state!!.query)
        assertEquals("find cat", calls.last())
    }

    @Test
    fun anotherTabEndsTheSearch() {
        find.onPage(1, "https://example.com/", loading = false)
        find.open()
        find.setQuery("cat")
        find.onPage(2, "https://other.example/", loading = false)
        assertFalse(find.isOpen)
        assertEquals("clear", calls.last())
    }

    @Test
    fun searchesAgainWhenThePageChanges() {
        find.onPage(1, "https://example.com/a", loading = false)
        find.open()
        find.setQuery("cat")
        report("cat", 1, 3)
        //a jump to an anchor is the same page
        find.onPage(1, "https://example.com/a#part", loading = false)
        assertEquals(listOf("find cat"), calls)
        //a new load: the matches come when it has loaded
        find.onPage(1, "https://example.com/b", loading = true)
        assertNull(find.state!!.result)
        assertEquals(listOf("find cat"), calls)
        find.onPage(1, "https://example.com/b", loading = false)
        assertEquals(listOf("find cat", "find cat"), calls)
        //a page that changes its address itself (history.pushState) loads nothing
        find.onPage(1, "https://example.com/c", loading = false)
        assertEquals(listOf("find cat", "find cat", "find cat"), calls)
    }

    @Test
    fun opensWhileThePageLoadsAndSearchesWhenItHasLoaded() {
        find.onPage(1, "https://example.com/", loading = false)
        find.open()
        find.setQuery("cat")
        find.close()
        find.onPage(1, "https://example.com/next", loading = true)
        find.open()
        assertEquals(listOf("find cat", "clear"), calls)
        find.onPage(1, "https://example.com/next", loading = false)
        assertEquals("find cat", calls.last())
    }

    @Test
    fun countsMatches() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertNull(FindInPageController.State("cat").countText(resources))
        assertEquals("3/17", FindInPageController.State("cat", FindResult("cat", 3, 17)).countText(resources))
        assertEquals("0/0", FindInPageController.State("cat", FindResult("cat", 0, 0)).countText(resources))
        assertEquals("3/1000+",
            FindInPageController.State("cat", FindResult("cat", 3, FindResult.MANY)).countText(resources))
    }
}
