package com.phlox.tvwebbrowser.utils

import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.Reader
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class FaviconExtractorTest {
    private val extractor = FaviconExtractor()

    @Test
    fun iconInfoSizesParsing() {
        val info = FaviconExtractor.IconInfo(
            "test/test.ico",//relative path
            FaviconExtractor.DEFAULT_ICON_TYPE,
            null,
            "32x32",
            URL("http://example.com/folder/index.html")
        )
        assertEquals(32, info.width)
        assertEquals(info.width, info.height)
        assertEquals("http://example.com/folder/test/test.ico", info.src)

        val info2 = FaviconExtractor.IconInfo(
            "/test/test.ico",//path from root
            null,
            null,
            "any",//in case of any string in non-00x00 format
            URL("http://example.com/folder/index.html")
        )
        assertEquals(0, info2.width)
        assertEquals("http://example.com/test/test.ico", info2.src)
    }

    @Test
    fun extractFavIconsFromHTML() {
        val (icons, manifestHref) = extractor.extractFavIconsFromHTML(null, """<html>
            <head>
            <link rel="icon" class="js-site-favicon" href="https://github.githubassets.com/favicons/favicon.svg">
            <link rel="manifest" href="/manifest.json" crossOrigin="use-credentials">
            </head>
        </html>""".trimMargin().reader().buffered())
        assertEquals(1, icons.size)
        assertEquals("icon", icons[0].rel)
        assertEquals("image/svg+xml", icons[0].type)
        assertNotNull(manifestHref)
    }

    @Test
    fun extractFavIconsFromHTMLReadsLimitedAmountOfHugeInput() {
        val head = """<html><head><link rel="icon" href="https://example.com/icon.png">"""
        //no closing head tag and no line breaks
        val totalChars = 16 * 1024 * 1024
        var charsRead = 0
        val source = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (charsRead >= totalChars) return -1
                val count = minOf(len, totalChars - charsRead)
                for (i in 0 until count) {
                    val pos = charsRead + i
                    cbuf[off + i] = if (pos < head.length) head[pos] else 'a'
                }
                charsRead += count
                return count
            }

            override fun close() {}
        }

        val (icons, _) = extractor.extractFavIconsFromHTML(null, source.buffered())

        assertTrue("read $charsRead chars", charsRead <= FaviconExtractor.MAX_HTML_HEADER_CHARS + 64 * 1024)
        assertEquals(1, icons.size)
        assertEquals("https://example.com/icon.png", icons[0].src)
    }

    @Test
    fun extractFavIconsFromWebManifest() {
        val icons = extractor.extractFavIconsFromWebManifest(URL("http://example.com/test/manifest.json"), """
            {
              "short_name": "Weather",
              "name": "Weather: Do I need an umbrella?",
              "icons": [
                {
                  "src": "/images/icons-vector.svg",
                  "type": "image/svg+xml",
                  "sizes": "512x512"
                },
                {
                  "src": "images/icons-192.png",
                  "type": "image/png",
                  "sizes": "192x192"
                }
              ],
              "id": "/?source=pwa",
              "description": "Weather forecast information"
            }
        """.trimIndent().reader())

        assertEquals(2, icons.size)
        assertEquals("http://example.com/images/icons-vector.svg", icons[0].src)
        assertEquals("http://example.com/test/images/icons-192.png", icons[1].src)
    }

    /**
     * Large integration test, may fail if third party pages changed (it is ok)
     * Comment @Ignore annotation if want to run
     */
    @Ignore
    @Test
    fun extractFavIconsFromURL() {
        var icons = extractor.extractFavIconsFromURL(
            URL("https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser")
        )
        assertEquals(11, icons.size)

        icons = extractor.extractFavIconsFromURL(
            URL("https://uibakery.io/regex-library/html-regex-java")
        )
        assertEquals(3, icons.size)

        icons = extractor.extractFavIconsFromURL(
            URL("https://github.com/bumptech/glide/issues/2152")
        )
        assertEquals(14, icons.size)
    }
}