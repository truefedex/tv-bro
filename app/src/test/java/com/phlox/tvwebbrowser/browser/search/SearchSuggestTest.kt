package com.phlox.tvwebbrowser.browser.search

import com.phlox.tvwebbrowser.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class SearchSuggestTest {
    private class FakeConnection(
        url: URL, private val code: Int, private val body: ByteArray, private val type: String?
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentType() = type
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private var opened: FakeConnection? = null

    private fun client(body: ByteArray, code: Int = 200, type: String? = "application/json; charset=utf-8") =
        SearchSuggestClient(open = { url -> FakeConnection(url, code, body, type).also { opened = it } }, language = { "uk-UA" })

    @Test
    fun theAnswersOfTheEnginesAreRead() {
        //Google and Yandex add more elements, Bing and DuckDuckGo give only the two
        assertEquals(listOf("weather", "weather radar"), SearchSuggestParser.parse(
            """["weath",["weather","weather radar"],[],{"google:suggestsubtypes":[[512,402],[512]]}]"""))
        assertEquals(listOf("weather", "weather today"), SearchSuggestParser.parse(
            """["weath",["weather","weather today"],["",""],[],{"google:suggesttype":["QUERY","QUERY"]}]"""))
        assertEquals(listOf("погода", "погода київ"), SearchSuggestParser.parse("""["пого",["погода","погода київ"]]"""))
        assertEquals(listOf("weather"), SearchSuggestParser.parse("""["weath",["weather"],[],[]]""" + "\n"))
    }

    @Test
    fun whatIsNotSuggestionsGivesNone() {
        assertTrue(SearchSuggestParser.parse("").isEmpty())
        assertTrue(SearchSuggestParser.parse("<html><body>Sign in</body></html>").isEmpty())
        assertTrue(SearchSuggestParser.parse("""{"suggestions":["a"]}""").isEmpty())
        assertTrue(SearchSuggestParser.parse("""["a"]""").isEmpty())
        assertTrue(SearchSuggestParser.parse("""["a","b"]""").isEmpty())
        //only the strings are taken, trimmed; blank and very long ones are left out
        assertEquals(listOf("one", "two"), SearchSuggestParser.parse(
            """["a",["one",2,null,{"phrase":"x"},["y"],"  ","${"z".repeat(300)}"," two "]]"""))
    }

    @Test
    fun theSuggestionsAreLimited() {
        val many = (1..50).joinToString(",") { "\"s$it\"" }
        assertEquals(SearchSuggestParser.MAX_SUGGESTIONS, SearchSuggestParser.parse("""["s",[$many]]""").size)
    }

    @Test
    fun theTextGoesEncodedInPlaceOfTheQuery() {
        val client = client("""["a b",["a b c"]]""".toByteArray())
        assertEquals(listOf("a b c"), client.fetch("https://example.com/s?q=[query]&x=1", "a b&c=д"))
        val connection = opened!!
        assertEquals("https://example.com/s?q=a+b%26c%3D%D0%B4&x=1", connection.url.toString())
        assertEquals("uk-UA", connection.getRequestProperty("Accept-Language"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun theCharsetOfTheAnswerIsUsed() {
        val body = """["по",["погода"]]""".toByteArray(charset("windows-1251"))
        assertEquals(listOf("погода"), client(body, type = "text/javascript; charset=windows-1251").fetch("https://e.com/?q=[query]", "по"))
        assertEquals(Charsets.UTF_8, SearchSuggestClient.charsetOf(null))
        assertEquals(Charsets.UTF_8, SearchSuggestClient.charsetOf("application/json"))
        assertEquals(Charsets.UTF_8, SearchSuggestClient.charsetOf("application/json; charset=no-such"))
        assertEquals(Charsets.ISO_8859_1, SearchSuggestClient.charsetOf("text/javascript; Charset=\"ISO-8859-1\""))
    }

    @Test
    fun errorsAndLongAnswersFail() {
        fun fails(client: SearchSuggestClient, template: String = "https://e.com/?q=[query]") = try {
            client.fetch(template, "a")
            false
        } catch (e: IOException) {
            true
        }
        assertTrue(fails(client("[]".toByteArray(), code = 500)))
        //a refusal is told apart: the engine is left alone after it
        for (code in listOf(429, 403)) {
            try {
                client("[]".toByteArray(), code = code).fetch("https://e.com/?q=[query]", "a")
                org.junit.Assert.fail()
            } catch (e: SearchSuggestRefusedException) {
                assertEquals(code, e.code)
            }
        }
        assertTrue(fails(client(ByteArray(SearchSuggestClient.MAX_BODY_BYTES + 1) { ' '.code.toByte() })))
        //only web addresses with the query are asked
        opened = null
        assertTrue(fails(client("[]".toByteArray()), "file:///sdcard/[query]"))
        assertTrue(fails(client("[]".toByteArray()), "https://e.com/suggest"))
        assertNull(opened)
    }

    @Test
    fun searchesAreAskedAndAddressesAreNot() {
        for (text in listOf("weather", "weather kyiv", " yout", "что такое dns", "python: lambda", "a/b c", "c++", "we", "猫")) {
            assertTrue(text, SearchSuggestRules.shouldAsk(text))
        }
        for (text in listOf("", "   ", "w", " w ", "я", "https://example.com", "http://", "see https://example.com/a b", "example.com",
            "example.", "www.example.com/path", "192.168.1.1", "router/admin", "localhost", "LOCALHOST", "host:8080",
            "about:config", "x".repeat(SearchSuggestRules.MAX_LENGTH + 1))) {
            assertFalse(text, SearchSuggestRules.shouldAsk(text))
        }
    }

    @Test
    fun anEngineThatRefusesOrKeepsFailingIsLeftAlone() {
        var now = 1_000_000L
        val limiter = SearchSuggestLimiter { now }
        assertTrue(limiter.tryRequest("a.com"))
        limiter.onError("a.com")
        assertFalse(limiter.isPaused("a.com"))
        //an answer between the failures starts the count again
        limiter.onAnswer("a.com")
        limiter.onError("a.com")
        assertFalse(limiter.isPaused("a.com"))
        limiter.onError("a.com")
        assertTrue(limiter.isPaused("a.com"))
        assertFalse(limiter.tryRequest("a.com"))
        //the other engines are asked
        assertTrue(limiter.tryRequest("b.com"))
        limiter.onRefused("b.com")
        assertTrue(limiter.isPaused("b.com"))
        now += SearchSuggestLimiter.PAUSE_MS - 1
        assertTrue(limiter.isPaused("a.com"))
        now += 1
        assertFalse(limiter.isPaused("a.com"))
        assertTrue(limiter.tryRequest("a.com"))
        //one more failure does not pause it at once
        limiter.onError("a.com")
        assertFalse(limiter.isPaused("a.com"))
    }

    @Test
    fun theRequestsOfAMinuteAreLimited() {
        var now = 1_000_000L
        val limiter = SearchSuggestLimiter { now }
        repeat(SearchSuggestLimiter.MAX_PER_MINUTE) {
            assertTrue(limiter.tryRequest(if (it % 2 == 0) "a.com" else "b.com"))
            now += 1000
        }
        assertFalse(limiter.tryRequest("a.com"))
        assertFalse(limiter.tryRequest("c.com"))
        //no pause: the first request of the minute is 60 s old now
        assertFalse(limiter.isPaused("a.com"))
        now += 60_000 - SearchSuggestLimiter.MAX_PER_MINUTE * 1000
        assertTrue(limiter.tryRequest("a.com"))
        assertFalse(limiter.tryRequest("a.com"))
    }

    @Test
    fun theSourceOfTheAddressFieldFollowsTheSettingsAndTheLimits() = kotlinx.coroutines.runBlocking {
        val config = com.phlox.tvwebbrowser.AppContext.provideConfig()
        config.searchEngineURL.value = Config.SearchEnginesURLs[0]
        var code = 200
        var requests = 0
        val client = SearchSuggestClient(open = { url ->
            requests++
            FakeConnection(url, code, """["we",["weather"]]""".toByteArray(), "application/json")
        })
        val limiter = SearchSuggestLimiter()
        val source = SearchEngineSuggestions(config, client, kotlin.coroutines.EmptyCoroutineContext, limiter)
        assertTrue(source.asks("we"))
        assertFalse(source.asks("w"))
        assertFalse(source.asks("example.com"))
        assertEquals(listOf("weather"), source.suggest("we"))

        config.searchSuggestions = false
        assertFalse(source.asks("we"))
        assertNull(source.suggest("we"))
        config.searchSuggestions = true
        config.incognitoMode = true
        assertFalse(source.asks("we"))
        assertNull(source.suggest("we"))
        config.incognitoMode = false
        assertEquals(1, requests)

        //refused: the engine is not asked again, another one is
        code = 429
        assertNull(source.suggest("we"))
        assertEquals(2, requests)
        assertFalse(source.asks("we"))
        assertNull(source.suggest("we"))
        assertEquals(2, requests)
        code = 200
        config.searchEngineURL.value = Config.SearchEnginesURLs[1]
        assertTrue(source.asks("we"))
        assertEquals(listOf("weather"), source.suggest("we"))
    }

    @Test
    fun theEnginesOfTheListHaveTheirSuggestions() {
        assertEquals(Config.SearchEnginesURLs.size, Config.SearchEnginesSuggestURLs.size)
        for ((index, url) in Config.SearchEnginesURLs.withIndex()) {
            if (url.isEmpty()) continue
            val suggest = SearchEngines.suggestUrlOf(url, "")
            assertEquals(Config.SearchEnginesSuggestURLs[index], suggest)
            assertTrue(SearchEngines.isSuggestUrl(suggest!!))
            //the address the user gave for another engine does not apply
            assertEquals(suggest, SearchEngines.suggestUrlOf(url, "https://other.com/?q=[query]"))
        }
    }

    @Test
    fun qwantSuggestsInTheLanguageOfTheUser() {
        val qwant = Config.SearchEnginesURLs[Config.QWANT_INDEX]
        val template = SearchEngines.suggestUrlOf(qwant, "")!!
        assertEquals(Config.SearchEnginesSuggestURLs[Config.QWANT_INDEX], template)
        assertTrue(SearchEngines.isSuggestUrl(template))
        assertEquals(template, SearchEngines.suggestUrlOf("https://www.qwant.com/?q=[query]&l=fr", ""))
        val client = SearchSuggestClient(open = { url ->
            FakeConnection(url, 200, """["car",["carrefour"]]""".toByteArray(), "application/json").also { opened = it }
        }, suggestLang = { "fr_fr" })
        assertEquals(listOf("carrefour"), client.fetch(template, "car"))
        assertEquals("https://api.qwant.com/api/suggest/?q=car&client=opensearch&lang=fr_fr", opened!!.url.toString())
    }

    @Test
    fun theEnginesOfTheListHaveTheirHomePages() {
        assertEquals(Config.SearchEnginesURLs.size, Config.SearchEnginesHomeURLs.size)
        assertEquals(Config.SearchEnginesURLs.size, Config.SearchEnginesTitles.size)
        assertEquals(Config.SearchEnginesURLs.size, Config.SearchEnginesNames.size)
        assertEquals("", Config.SearchEnginesURLs.last())
        assertEquals("https://www.bing.com", Config.searchEngineHomeUrl(Config.SearchEnginesURLs[1]))
        assertEquals(Config.SearchEnginesHomeURLs[Config.QWANT_INDEX],
            Config.searchEngineHomeUrl(Config.SearchEnginesURLs[Config.QWANT_INDEX]))
    }

    @Test
    fun theUsersEngineHasTheSuggestionsGivenOrThoseOfItsKnownHost() {
        val custom = "https://search.example.com/?q=[query]"
        assertNull(SearchEngines.suggestUrlOf(custom, ""))
        assertEquals("https://search.example.com/ac?q=[query]",
            SearchEngines.suggestUrlOf(custom, " https://search.example.com/ac?q=[query] "))
        //an address that can not be asked gives none
        assertNull(SearchEngines.suggestUrlOf(custom, "https://search.example.com/ac"))
        assertNull(SearchEngines.suggestUrlOf(custom, "javascript:[query]"))
        assertEquals(Config.SearchEnginesSuggestURLs[0], SearchEngines.suggestUrlOf("https://www.google.com.ua/search?q=[query]&hl=uk", ""))
        assertEquals(Config.SearchEnginesSuggestURLs[4], SearchEngines.suggestUrlOf("https://ya.ru/search/?text=[query]", ""))
        assertEquals(Config.SearchEnginesSuggestURLs[3], SearchEngines.suggestUrlOf("https://html.duckduckgo.com/html?q=[query]", ""))
        assertNull(SearchEngines.suggestUrlOf("https://mygoogle.example.com/?q=[query]", ""))
        assertNull(SearchEngines.suggestUrlOf("", ""))
        assertNull(SearchEngines.suggestUrlOf("not an address", ""))
    }
}
