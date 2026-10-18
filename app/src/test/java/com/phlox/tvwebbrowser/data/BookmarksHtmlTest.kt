package com.phlox.tvwebbrowser.data

import com.phlox.tvwebbrowser.data.BookmarksHtml.Folder
import com.phlox.tvwebbrowser.data.BookmarksHtml.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksHtmlTest {
    @Test
    fun foldersAndBookmarksComeBackAsTheyWereWritten() {
        val nodes = listOf(
            Folder("Home page", listOf(Link("Tile", "https://tile.com/")), isHomePage = true),
            Folder("Outer", listOf(
                Folder("Inner", listOf(Link("Deep", "https://deep.com/a?b=1&c=2"))),
                Folder("Empty", emptyList()),
                Link("Tom & Jerry <\"quoted\"> 'single'", "https://example.com/?q=\"x\"&y=<z>")
            )),
            Link("Українська правда", "https://www.pravda.com.ua/")
        )
        assertEquals(nodes, BookmarksHtml.parse(BookmarksHtml.write(nodes)))
    }

    @Test
    fun theHomePageFolderIsMarkedForTvBro() {
        val html = BookmarksHtml.write(listOf(
            Folder("Home page", listOf(Link("a", "https://a.com/")), isHomePage = true),
            Folder("Other", emptyList())
        ))
        assertTrue(html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        assertTrue(html.contains("<DT><H3 TVBRO_HOME_PAGE=\"true\">Home page</H3>"))
        assertTrue(html.contains("<DT><H3>Other</H3>"))
        assertTrue(html.contains("<DT><A HREF=\"https://a.com/\">a</A>"))
    }

    @Test
    fun aChromeFileIsRead() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <!-- This is an automatically generated file.
                 It will be read and overwritten.
                 DO NOT EDIT! -->
            <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
            <TITLE>Bookmarks</TITLE>
            <H1>Bookmarks</H1>
            <DL><p>
                <DT><H3 ADD_DATE="1700000000" LAST_MODIFIED="1700000001" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
                <DL><p>
                    <DT><A HREF="https://www.youtube.com/" ADD_DATE="1700000002" ICON="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAOklEQVQ4jWNgGAWjYBSMglEwCkbBKBgFo2AUjIJRMApGwSgYBaNgFIyCUTAKRsEoGAWjYBSMglEwCkbBKBgFIwAAZQkCAZqXLbgAAAAASUVORK5CYII=">YouTube</A>
                    <DT><H3 ADD_DATE="1700000003" LAST_MODIFIED="0">News</H3>
                    <DL><p>
                        <DT><A HREF="https://www.bbc.com/news" ADD_DATE="1700000004">BBC &amp; more</A>
                    </DL><p>
                </DL><p>
                <DT><H3 ADD_DATE="1700000005" LAST_MODIFIED="0">Other bookmarks</H3>
                <DL><p>
                    <DT><A HREF="https://example.com/?a=1&amp;b=2" ADD_DATE="1700000006">Example</A>
                </DL><p>
            </DL><p>
        """.trimIndent()
        assertEquals(listOf(
            Folder("Bookmarks bar", listOf(
                Link("YouTube", "https://www.youtube.com/"),
                Folder("News", listOf(Link("BBC & more", "https://www.bbc.com/news")))
            )),
            Folder("Other bookmarks", listOf(Link("Example", "https://example.com/?a=1&b=2")))
        ), BookmarksHtml.parse(html))
    }

    @Test
    fun aFirefoxFileIsReadWithoutItsQueriesAndDescriptions() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <!-- This is an automatically generated file.
                 It will be read and overwritten.
                 DO NOT EDIT! -->
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
            <meta http-equiv="Content-Security-Policy"
                  content="default-src 'self'; script-src 'none'; img-src data: *; object-src 'none'"></meta>
            <TITLE>Bookmarks</TITLE>
            <H1>Bookmarks Menu</H1>

            <DL><p>
                <DT><A HREF="place:parent=menu________&queryType=1&sort=12&maxResults=10&excludeQueries=1" ADD_DATE="1" LAST_MODIFIED="2">Recent Tags</A>
                <HR>
                <DT><H3 ADD_DATE="1" LAST_MODIFIED="2">Mozilla Firefox</H3>
                <DL><p>
                    <DT><A HREF="https://support.mozilla.org/products/firefox" ADD_DATE="1" LAST_MODIFIED="2" ICON_URI="https://support.mozilla.org/favicon.ico" ICON="data:image/png;base64,AAAA">Get Help</A>
                </DL><p>
                <DT><H3 ADD_DATE="1" LAST_MODIFIED="2" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks Toolbar</H3>
                <DD>Add bookmarks to this folder to see them displayed on the Bookmarks Toolbar
                <DL><p>
                    <DT><A HREF="https://www.mozilla.org/" ADD_DATE="1" LAST_MODIFIED="2">Mozilla &#8211; Home</A>
                    <DD>The home of Mozilla
                </DL><p>
            </DL>
        """.trimIndent()
        assertEquals(listOf(
            Folder("Mozilla Firefox", listOf(Link("Get Help", "https://support.mozilla.org/products/firefox"))),
            Folder("Bookmarks Toolbar", listOf(Link("Mozilla \u2013 Home", "https://www.mozilla.org/")))
        ), BookmarksHtml.parse(html))
    }

    @Test
    fun looseHtmlIsReadToo() {
        val html = "\uFEFF<dl><dt><h3 tvbro_home_page=TRUE>Home \n  page</h3><dl>" +
            "<dt><a href=https://a.com/>A&#x41;&#66;</a>" +
            "<dt><a href='https://b.com/'>B&nbsp;b" +
            "<dt><a href=\"https://c.com/\"></a>" +
            "<dt><a>no address</a>" +
            "</dl><dt><h3>Not closed<dl><dt><a href=\"https://d.com/\">&unknown; &lt;d&gt;</a></dl></dl>"
        assertEquals(listOf(
            Folder("Home page", listOf(
                Link("AAB", "https://a.com/"),
                Link("B b", "https://b.com/"),
                Link("https://c.com/", "https://c.com/")
            ), isHomePage = true),
            Folder("Not closed", listOf(Link("&unknown; <d>", "https://d.com/")))
        ), BookmarksHtml.parse(html))
    }

    @Test
    fun aFileWithoutBookmarksGivesNothing() {
        assertEquals(emptyList<BookmarksHtml.Node>(), BookmarksHtml.parse(""))
        assertEquals(emptyList<BookmarksHtml.Node>(), BookmarksHtml.parse("<html><body><p>Hello</p></body></html>"))
        //an unclosed tag at the end
        assertEquals(emptyList<BookmarksHtml.Node>(), BookmarksHtml.parse("<DL><DT><A HREF=\"https://a.com/"))
    }
}
