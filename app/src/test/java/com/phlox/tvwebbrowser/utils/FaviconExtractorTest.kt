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
    fun extractFavIconsFromHTMLReadsQuotedValuesWithSpaces() {
        val (icons, manifestHref) = extractor.extractFavIconsFromHTML(URL("https://example.com/a/page.html"), """<HTML><HEAD>
            <link rel="alternate icon" href="/alt.png">
            <link rel="shortcut icon" href="/favicon.ico">
            <link rel='icon' sizes='16x16 32x32 48x48' href='/multi.ico'>
            <LINK REL=icon SIZES=any HREF=/any.png>
            <link rel="apple-touch-icon-precomposed" href="/touch.png?a=1&amp;b=2">
            <link rel="mask-icon" href="/mask.svg" color="#000">
            <link rel="stylesheet" href="/style.css">
            <link href="/m.webmanifest" rel="manifest">
            </HEAD><BODY><link rel="icon" href="/body.png"></BODY></HTML>""".reader().buffered())
        assertEquals(listOf(
            "https://example.com/alt.png",
            "https://example.com/favicon.ico",
            "https://example.com/multi.ico",
            "https://example.com/any.png",
            "https://example.com/touch.png?a=1&b=2"
        ), icons.map { it.src })
        assertEquals(48, icons[2].width)
        assertEquals(0, icons[3].width)
        assertEquals(FaviconExtractor.APPLE_TOUCH_ICON_SIZE, icons[4].width)
        assertEquals("/m.webmanifest", manifestHref)
    }

    @Test
    fun badIconAddressDoesNotLoseTheOthers() {
        val (icons, _) = extractor.extractFavIconsFromHTML(URL("https://example.com/"), """<head>
            <link rel="icon" href="bad://[x">
            <link rel="icon" href="data:image/png;base64,iVBORw0KGgo=">
            <link rel="icon" href="/good.png">
            </head>""".reader().buffered())
        assertEquals(listOf("data:image/png;base64,iVBORw0KGgo=", "https://example.com/good.png"), icons.map { it.src })
    }

    @Test
    fun rankPrefersTheSmallestIconThatIsBigEnough() {
        val base = URL("https://example.com/")
        fun icon(src: String, sizes: String?, source: FaviconExtractor.Source = FaviconExtractor.Source.LINK,
                 maskable: Boolean = false) =
            FaviconExtractor.IconInfo(src, null, "icon", sizes, base, source, maskable)
        val icons = listOf(
            icon("/16.png", "16x16"),
            icon("/32.png", "32x32"),
            icon("/96.png", "96x96"),
            icon("/unknown.png", null),
            icon("/512.png", "512x512", FaviconExtractor.Source.MANIFEST),
            icon("/192-maskable.png", "192x192", FaviconExtractor.Source.MANIFEST, maskable = true),
            icon("/180.png", "180x180"),
            icon("/vector.svg", "512x512"),
            icon("/manifest-any.png", "any", FaviconExtractor.Source.MANIFEST),
            icon("/favicon.ico", null, FaviconExtractor.Source.DEFAULT)
        )
        val ranked = FaviconExtractor.rank(icons, 128, 64).map { it.src.removePrefix("https://example.com") }
        assertEquals(listOf(
            "/180.png", "/512.png", "/192-maskable.png",
            "/96.png",
            "/manifest-any.png", "/unknown.png", "/favicon.ico",
            "/32.png", "/16.png"
        ), ranked)
    }

    @Test
    fun manifestIconsWithSeveralSizesAndPurposes() {
        val icons = extractor.extractFavIconsFromWebManifest(URL("https://example.com/manifest.json"), """
            {
              "icons": [
                {"src": "a.png", "sizes": "48x48 96x96", "purpose": "any maskable"},
                {"src": "b.png", "sizes": "192x192", "purpose": "maskable"},
                {"src": "c.png", "sizes": 512}
              ]
            }
        """.trimIndent().reader())
        assertEquals(3, icons.size)
        assertEquals(96, icons[0].width)
        assertFalse(icons[0].maskable)
        assertTrue(icons[1].maskable)
        assertEquals(0, icons[2].width)
        assertTrue(icons.all { it.source == FaviconExtractor.Source.MANIFEST })
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