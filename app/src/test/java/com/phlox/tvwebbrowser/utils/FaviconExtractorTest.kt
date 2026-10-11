package com.phlox.tvwebbrowser.utils

import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Reader
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FaviconExtractorTest {
    private val extractor = FaviconExtractor()

    @Test
    fun unbrokenMediaLikeInputIsBounded() {
        var consumed = 0
        val endless = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                check(consumed + length <= FaviconExtractor.MAX_METADATA_CHARS + 8192) {
                    "Metadata parser attempted to consume an unbounded media response"
                }
                buffer.fill('x', offset, offset + length)
                consumed += length
                return length
            }
            override fun close() = Unit
        }
        val (icons, manifest) = extractor.extractFavIconsFromHTML(null, endless.buffered())
        assertTrue(icons.isEmpty())
        assertNull(manifest)
        assertTrue(consumed <= FaviconExtractor.MAX_METADATA_CHARS + 8192)
    }

    @Test
    fun videoResponseIsNotReadForFavicons() {
        val url = URL(null, "https://example.com/movie.mp4", object : URLStreamHandler() {
            override fun openConnection(url: URL) = object : URLConnection(url) {
                override fun connect() = Unit
                override fun getContentType() = "video/mp4"
                override fun getInputStream(): java.io.InputStream =
                    error("Video body must not be fetched for favicon discovery")
            }
        })
        val icons = extractor.extractFavIconsFromURL(url)
        assertEquals("https://example.com/favicon.ico", icons.single().src)
    }

    @Test
    fun closingHeadAcrossChunksStopsBeforeBodyIcons() {
        val header = "<link rel=\"icon\" href=\"/head.ico\" type=\"image/x-icon\">"
        val html = header.padEnd(4093, ' ') + "</HEAD><link rel=\"icon\" href=\"/body.ico\">"
        val (icons, _) = extractor.extractFavIconsFromHTML(null, html.reader().buffered())
        assertEquals("/head.ico", icons.single().src)
    }

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
    fun extractFavIconsFromWebManifest() {
        val icons = extractor.extractFavIconsFromWebManifest(URL("http://example.com/test/manifest.json"), """
            {
              "short_name": "Weather",
              "name": "Weather: Do I need an umbrella?",
              "icons": [
                {
                  "src": "/images/icons-vector.svg",
                  "type": "image/svg+xml",
                  "sizes": "512x512",
                  "purpose": "any maskable"
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