package com.phlox.tvwebbrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UrlInputResolverTest {
    private val google = "https://www.google.com/search?q=[query]"

    private fun resolve(input: String, template: String = google) =
        UrlInputResolver.resolve(input, template)

    @Test
    fun addressWithoutSchemeGetsHttps() {
        assertEquals("https://example.com", resolve("example.com"))
        assertEquals("https://www.example.com/search?q=1", resolve("www.example.com/search?q=1"))
        assertEquals("https://192.168.1.1", resolve("192.168.1.1"))
    }

    @Test
    fun addressWithSchemeIsKeptAsIs() {
        assertEquals("http://example.com/path", resolve("http://example.com/path"))
        assertEquals("HTTPS://Example.com", resolve("HTTPS://Example.com"))
        assertEquals("ftp://example.com", resolve("ftp://example.com"))
    }

    @Test
    fun textIsSearched() {
        assertEquals("https://www.google.com/search?q=hello+world", resolve("hello world"))
        assertEquals("https://www.google.com/search?q=c%2B%2B+tutorial", resolve("c++ tutorial"))
        assertEquals("https://duckduckgo.com/?q=kotlin", resolve("kotlin", "https://duckduckgo.com/?q=[query]"))
    }

    @Test
    fun templateWithoutPlaceholderIsReturnedAsIs() {
        assertEquals("https://example.com/", resolve("kotlin", "https://example.com/"))
    }

    // current behavior: the check for a web address uses the untrimmed text
    @Test
    fun surroundingSpacesTurnAddressIntoSearch() {
        assertEquals("https://www.google.com/search?q=+example.com+", resolve(" example.com "))
    }

    // current behavior: http(s) prefix is checked on the trimmed text, but the untrimmed text is returned
    @Test
    fun leadingSpacesAreKeptForHttpAddress() {
        assertEquals("  http://example.com", resolve("  http://example.com"))
    }

    // current behavior: addresses without a TLD and non-web schemes are searched
    @Test
    fun nonWebAddressesAreSearched() {
        assertEquals("https://www.google.com/search?q=localhost%3A8080", resolve("localhost:8080"))
        assertEquals("https://www.google.com/search?q=about%3Ablank", resolve("about:blank"))
        assertEquals("https://www.google.com/search?q=file%3A%2F%2F%2Fsdcard%2Fa.html", resolve("file:///sdcard/a.html"))
    }
}
