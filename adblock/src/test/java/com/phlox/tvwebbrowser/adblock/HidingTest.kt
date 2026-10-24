package com.phlox.tvwebbrowser.adblock

import com.phlox.tvwebbrowser.adblock.TestPsl.compile
import com.phlox.tvwebbrowser.adblock.TestPsl.psl
import com.phlox.tvwebbrowser.adblock.engine.CosmeticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HidingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun FilterEngine.page(host: String) = hidingStyles("https://$host/", host)
    private fun FilterEngine.generic(host: String, classes: List<String> = emptyList(), ids: List<String> = emptyList()) =
        genericHidingStyles("https://$host/", host, classes, ids)

    @Test
    fun specificSelectorsOfTheSiteAndItsSubdomains() {
        val e = compile("example.com##.banner", "other.org##.promo")
        assertTrue(e.page("example.com").css.contains(".banner{display:none!important}"))
        assertTrue(e.page("news.example.com").css.contains(".banner"))
        assertFalse(e.page("example.com").css.contains(".promo"))
        assertEquals("", e.page("unknown.net").css)
    }

    @Test
    fun excludedSubdomains() {
        val e = compile("example.com,~news.example.com##.banner")
        assertTrue(e.page("www.example.com").css.contains(".banner"))
        assertFalse(e.page("news.example.com").css.contains(".banner"))
    }

    @Test
    fun genericSelectorsByClassAndIdAreAskedFor() {
        val e = compile("##.ad-box", "##div#top-ad > a", "##[data-ad-slot]")
        val page = e.page("site.com")
        //every page gets the selectors without a class or id at once
        assertTrue(page.css.contains("[data-ad-slot]"))
        assertFalse(page.css.contains(".ad-box"))
        assertTrue(page.generic)
        assertEquals(".ad-box{display:none!important}\n", e.generic("site.com", classes = listOf("ad-box", "content")))
        assertTrue(e.generic("site.com", ids = listOf("top-ad")).contains("div#top-ad > a"))
        assertEquals("", e.generic("site.com", classes = listOf("top-ad")))
    }

    @Test
    fun genericSelectorsWithExcludedSites() {
        val e = compile("~example.com##.ad-box", "~example.com##[data-ad]")
        assertTrue(e.generic("site.com", classes = listOf("ad-box")).contains(".ad-box"))
        assertEquals("", e.generic("example.com", classes = listOf("ad-box")))
        assertTrue(e.page("site.com").css.contains("[data-ad]"))
        assertFalse(e.page("example.com").css.contains("[data-ad]"))
    }

    @Test
    fun exceptionsOfASite() {
        val e = compile("##.ad-box", "##[data-ad]", "example.com##.side", "example.com#@#.ad-box", "example.com#@#[data-ad]",
            "example.com#@#.side")
        assertEquals("", e.generic("example.com", classes = listOf("ad-box")))
        assertFalse(e.page("example.com").css.contains("[data-ad]"))
        assertFalse(e.page("example.com").css.contains(".side"))
        assertTrue(e.generic("site.com", classes = listOf("ad-box")).contains(".ad-box"))
        assertTrue(e.page("site.com").css.contains("[data-ad]"))
    }

    @Test
    fun exceptionsWithoutDomainsHideTheSelectorNowhere() {
        val e = compile("##.ad-box", "example.com##.ad-box", "#@#.ad-box")
        assertEquals("", e.generic("site.com", classes = listOf("ad-box")))
        assertEquals("", e.page("example.com").css)
    }

    @Test
    fun pageExceptions() {
        val e = compile("##.ad-box", "##[data-ad]", "example.com##.side", "news.org##.side", "@@||example.com^\$elemhide",
            "@@||news.org^\$generichide", "@@||trusted.net^\$document", "trusted.net##.side")
        assertEquals(HidingStyles.NONE.css, e.page("example.com").css)
        assertFalse(e.page("example.com").generic)
        assertEquals("", e.page("trusted.net").css)
        //generichide keeps the selectors of the site
        val news = e.page("news.org")
        assertEquals(".side{display:none!important}\n", news.css)
        assertFalse(news.generic)
        assertEquals("", e.generic("news.org", classes = listOf("ad-box")))
    }

    @Test
    fun selectorsAreSplitIntoRules() {
        val lines = (1..120).map { "example.com##.ad-$it" }.toTypedArray()
        val css = compile(*lines).page("example.com").css
        assertEquals(3, css.lines().count { it.isNotEmpty() })
        assertEquals(120, Regex("""\.ad-\d+""").findAll(css).count())
    }

    @Test
    fun nonAsciiSelectorsAndDomains() {
        val e = compile("пример.рф##.реклама")
        assertTrue(e.hidingStyles("https://xn--e1afmkfd.xn--p1ai/", "xn--e1afmkfd.xn--p1ai").css.contains(".реклама"))
    }

    @Test
    fun hidingSurvivesTheFile() {
        val file = File(temp.root, "engine.bin")
        compile("example.com##.banner", "##.ad-box", "##[data-ad]", "example.com#@#[data-ad]").writeTo(file)
        val e = FilterEngine.load(file, psl)
        assertEquals(".banner{display:none!important}\n", e.page("example.com").css)
        assertTrue(e.page("site.com").css.contains("[data-ad]"))
        assertTrue(e.generic("site.com", classes = listOf("ad-box")).contains(".ad-box"))
        assertEquals(3, e.hidingSelectorCount)
    }

    @Test
    fun keysOfGenericSelectors() {
        assertNotNull(CosmeticIndex.genericKey(".ad"))
        assertEquals(CosmeticIndex.genericKey(".ad"), CosmeticIndex.genericKey("div.ad > span"))
        assertEquals(CosmeticIndex.genericKey("#top"), CosmeticIndex.genericKey("DIV#top[style]"))
        assertNull(CosmeticIndex.genericKey("[data-ad]"))
        assertNull(CosmeticIndex.genericKey("AD-SLOT"))
        assertNull(CosmeticIndex.genericKey(".\\[\\&_x"))
        assertFalse(CosmeticIndex.genericKey(".ad") == CosmeticIndex.genericKey("#ad"))
    }
}
