package com.phlox.tvwebbrowser.adblock

import com.phlox.tvwebbrowser.adblock.MatchResult.ALLOW
import com.phlox.tvwebbrowser.adblock.MatchResult.BLOCK
import com.phlox.tvwebbrowser.adblock.MatchResult.NONE
import com.phlox.tvwebbrowser.adblock.TestPsl.compile
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterEngineTest {

    // --- Hosts ---

    @Test
    fun hostFilterMatchesTheHostAndItsSubdomains() {
        val e = compile("||ads.example.net^")
        assertEquals(BLOCK, e.check("https://ads.example.net/banner.js"))
        assertEquals(BLOCK, e.check("https://cdn.ads.example.net/"))
        assertEquals(BLOCK, e.check("http://ads.example.net:8080/x"))
        assertEquals(NONE, e.check("https://notads.example.net/banner.js"))
        assertEquals(NONE, e.check("https://ads.example.net.other.com/"))
        assertEquals(NONE, e.check("https://example.net/?r=https://ads.example.net/"))
    }

    @Test
    fun hostAnchorWithoutSeparatorMatchesLongerHosts() {
        val e = compile("||example.com")
        assertEquals(BLOCK, e.check("https://example.community/"))
        assertEquals(BLOCK, e.check("https://www.example.com/"))
        assertEquals(NONE, e.check("https://notexample.com/"))
    }

    @Test
    fun hostAnchorWithPath() {
        val e = compile("||example.org/ads/")
        assertEquals(BLOCK, e.check("https://example.org/ads/1.js"))
        assertEquals(BLOCK, e.check("https://cdn.example.org/ads/1.js"))
        assertEquals(NONE, e.check("https://example.org/news/ads/1.js"))
        assertEquals(NONE, e.check("https://other.com/example.org/ads/"))
    }

    @Test
    fun hostsFileLine() {
        val e = compile("0.0.0.0 tracker.example.org")
        assertEquals(BLOCK, e.check("https://tracker.example.org/pixel.gif", types = ResourceType.IMAGE))
    }

    // --- Patterns ---

    @Test
    fun separator() {
        val e = compile("/banner^")
        assertEquals(BLOCK, e.check("https://x.com/banner?id=1"))
        assertEquals(BLOCK, e.check("https://x.com/banner/1"))
        assertEquals(BLOCK, e.check("https://x.com/banner"))
        assertEquals(NONE, e.check("https://x.com/banners"))
        assertEquals(NONE, e.check("https://x.com/banner.js"))
        assertEquals(NONE, e.check("https://x.com/banner-1"))
    }

    @Test
    fun startAndEndAnchors() {
        val start = compile("|https://ads.")
        assertEquals(BLOCK, start.check("https://ads.example.com/"))
        assertEquals(NONE, start.check("https://x.com/?u=https://ads.example.com/"))
        val end = compile(".swf|")
        assertEquals(BLOCK, end.check("https://x.com/a.swf", types = ResourceType.OBJECT))
        assertEquals(NONE, end.check("https://x.com/a.swf?x=1", types = ResourceType.OBJECT))
    }

    @Test
    fun endAnchorAfterWildcard() {
        val e = compile("/ads/*.gif|")
        assertEquals(BLOCK, e.check("https://x.com/ads/a.gif/b.gif"))
        assertEquals(BLOCK, e.check("https://x.com/ads/1.gif"))
        assertEquals(NONE, e.check("https://x.com/ads/1.gif?x"))
    }

    @Test
    fun wildcards() {
        val e = compile("/ads/*/banner*.gif")
        assertEquals(BLOCK, e.check("https://x.com/ads/123/banner_1.gif"))
        assertEquals(NONE, e.check("https://x.com/ads/banner_1.gif"))
    }

    @Test
    fun patternInsideTokens() {
        //no whole token to index it by: checked for every request
        val e = compile("ad_banner")
        assertEquals(BLOCK, e.check("https://x.com/img/top_ad_banner_2.png"))
        assertEquals(1, e.unkeyedFilterCounts[2])
    }

    @Test
    fun caseInsensitiveUnlessMatchCase() {
        val e = compile("/BannerAd.")
        assertEquals(BLOCK, e.check("https://x.com/BannerAd.gif"))
        assertEquals(BLOCK, e.check("https://x.com/bannerad.gif"))
        val m = compile("/BannerAd.\$match-case")
        assertEquals(BLOCK, m.check("https://x.com/BannerAd.gif"))
        assertEquals(NONE, m.check("https://x.com/bannerad.gif"))
    }

    @Test
    fun regularExpressions() {
        val e = compile("/banner\\d+\\.gif/")
        assertEquals(BLOCK, e.check("https://x.com/banner123.gif"))
        assertEquals(BLOCK, e.check("https://x.com/BANNER1.GIF"))
        assertEquals(NONE, e.check("https://x.com/banner.gif"))
        assertEquals(1, e.regexCount)
    }

    @Test
    fun regexWithARequiredLiteral() {
        val e = compile("/(https?:\\/\\/)104\\.154\\..{20,}/", "/Banner\\d+\\.GIF/\$match-case")
        assertEquals(BLOCK, e.check("http://104.154.1.2/aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals(NONE, e.check("http://104.154.1.2/a"))
        assertEquals(NONE, e.check("http://104.155.1.2/aaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals(BLOCK, e.check("https://x.com/Banner1.GIF"))
        assertEquals(NONE, e.check("https://x.com/banner1.gif"))
    }

    @Test
    fun nonAsciiPatternMatchesPercentEncodedUrls() {
        val e = compile("/реклама-")
        assertEquals(BLOCK, e.check("https://x.ru/%D1%80%D0%B5%D0%BA%D0%BB%D0%B0%D0%BC%D0%B0-1.js"))
        assertEquals(NONE, e.check("https://x.ru/news/1.js"))
    }

    // --- Exceptions and priorities ---

    @Test
    fun exceptionOverridesBlocking() {
        val e = compile("||ads.example.net^", "@@||ads.example.net/allowed^")
        assertEquals(ALLOW, e.check("https://ads.example.net/allowed/1.js"))
        assertEquals(BLOCK, e.check("https://ads.example.net/other/1.js"))
    }

    @Test
    fun exceptionAloneDoesNothing() {
        assertEquals(NONE, compile("@@||ads.example.net^").check("https://ads.example.net/1.js"))
    }

    @Test
    fun importantOverridesExceptions() {
        val e = compile("||ads.example.net^\$important", "@@||ads.example.net^")
        assertEquals(BLOCK, e.check("https://ads.example.net/1.js"))
    }

    @Test
    fun pageExceptionAllowsEverythingOnThePage() {
        val e = compile("||ads.example.net^", "||ads.example.net^\$important", "@@||example.com^\$document")
        assertEquals(ALLOW, e.check("https://ads.example.net/1.js", page = "https://www.example.com/news"))
        assertEquals(BLOCK, e.check("https://ads.example.net/1.js", page = "https://other.com/"))
        assertEquals(ResourceType.DOCUMENT, e.pageExceptions("https://example.com/", "example.com"))
    }

    @Test
    fun elementHidingPageExceptions() {
        val e = compile("@@||example.com^\$elemhide", "@@||news.example.org^\$generichide")
        assertEquals(FilterEngine.ELEMHIDE, e.pageExceptions("https://example.com/a", "example.com"))
        assertEquals(FilterEngine.GENERICHIDE, e.pageExceptions("https://news.example.org/", "news.example.org"))
        assertEquals(0, e.pageExceptions("https://other.org/", "other.org"))
        //they do not let requests through
        assertEquals(NONE, e.check("https://example.com/ads.js"))
    }

    // --- Options ---

    @Test
    fun thirdParty() {
        val e = compile("||cdn.example.org^\$third-party")
        assertEquals(NONE, e.check("https://cdn.example.org/a.js", page = "https://www.example.org/"))
        assertEquals(BLOCK, e.check("https://cdn.example.org/a.js", page = "https://example.com/"))
        //the party of a request without a page is not known
        assertEquals(NONE, e.check("https://cdn.example.org/a.js", page = null))
        val first = compile("||cdn.example.org^\$~third-party")
        assertEquals(BLOCK, first.check("https://cdn.example.org/a.js", page = "https://www.example.org/"))
        assertEquals(NONE, first.check("https://cdn.example.org/a.js", page = "https://example.com/"))
    }

    @Test
    fun thirdPartyUsesSitesNotHosts() {
        val e = compile("/track.js\$third-party")
        assertEquals(NONE, e.check("https://static.example.co.uk/track.js", page = "https://www.example.co.uk/"))
        assertEquals(BLOCK, e.check("https://other.co.uk/track.js", page = "https://www.example.co.uk/"))
        assertEquals(BLOCK, e.check("https://bob.github.io/track.js", page = "https://alice.github.io/"))
    }

    @Test
    fun domainsIncludedAndExcluded() {
        val e = compile("/ads.js\$domain=example.com|~sub.example.com")
        assertEquals(BLOCK, e.check("https://x.net/ads.js", page = "https://www.example.com/"))
        assertEquals(BLOCK, e.check("https://x.net/ads.js", page = "https://example.com/"))
        assertEquals(NONE, e.check("https://x.net/ads.js", page = "https://sub.example.com/"))
        assertEquals(NONE, e.check("https://x.net/ads.js", page = "https://a.sub.example.com/"))
        assertEquals(NONE, e.check("https://x.net/ads.js", page = "https://other.com/"))
        assertEquals(NONE, e.check("https://x.net/ads.js", page = "https://notexample.com/"))
    }

    @Test
    fun theMostSpecificDomainDecides() {
        val e = compile("/ads.js\$domain=~example.com|sub.example.com")
        assertEquals(BLOCK, e.check("https://x.net/ads.js", page = "https://sub.example.com/"))
        assertEquals(NONE, e.check("https://x.net/ads.js", page = "https://www.example.com/"))
        assertEquals(NONE, e.check("https://x.net/ads.js", page = "https://other.com/"))
    }

    @Test
    fun onlyExcludedDomains() {
        val e = compile("/ads.js\$domain=~example.com")
        assertEquals(BLOCK, e.check("https://x.net/ads.js", page = "https://other.com/"))
        assertEquals(NONE, e.check("https://x.net/ads.js", page = "https://example.com/"))
        assertEquals(BLOCK, e.check("https://x.net/ads.js", page = null))
    }

    @Test
    fun emptyPatternWithDomain() {
        val e = compile("\$script,third-party,domain=example.com")
        assertEquals(BLOCK, e.check("https://cdn.other.net/lib.js"))
        assertEquals(NONE, e.check("https://cdn.other.net/a.png", types = ResourceType.IMAGE))
        assertEquals(NONE, e.check("https://static.example.com/lib.js"))
        assertEquals(NONE, e.check("https://cdn.other.net/lib.js", page = "https://other.org/"))
    }

    @Test
    fun types() {
        val e = compile("||ads.example.net^\$image")
        assertEquals(NONE, e.check("https://ads.example.net/1", types = ResourceType.SCRIPT))
        assertEquals(BLOCK, e.check("https://ads.example.net/1", types = ResourceType.IMAGE))
        //a request that may be one of several types (WebView): a filter of one of them applies
        assertEquals(BLOCK, e.check("https://ads.example.net/1", types = ResourceType.IMAGE or ResourceType.OTHER))
    }

    @Test
    fun exceptionsApplyToTheirTypesToo() {
        val e = compile("||ads.example.net^", "@@||ads.example.net^\$script")
        assertEquals(ALLOW, e.check("https://ads.example.net/1", types = ResourceType.SCRIPT or ResourceType.XHR))
        assertEquals(BLOCK, e.check("https://ads.example.net/1", types = ResourceType.IMAGE))
    }

    @Test
    fun pagesAreBlockedOnlyByDocumentFilters() {
        val e = compile("||ads.example.net^", "||bad.example^\$document")
        assertEquals(NONE, e.check("https://ads.example.net/", page = null, types = ResourceType.DOCUMENT))
        assertEquals(BLOCK, e.check("https://bad.example/", page = null, types = ResourceType.DOCUMENT))
        assertEquals(NONE, e.check("https://bad.example/a.js"))
    }

    @Test
    fun filtersAreFoundByTheirTokens() {
        val e = compile("||example.org/ads/banner", "/adframe.", "-advert-")
        assertEquals(BLOCK, e.check("https://example.org/ads/banner.gif"))
        assertEquals(BLOCK, e.check("https://x.com/js/adframe.js"))
        assertEquals(BLOCK, e.check("https://x.com/img/top-advert-1.gif"))
        assertEquals(0, e.unkeyedFilterCounts.sum())
    }

    @Test
    fun matchedFilterIsKnownForDebugging() {
        val e = compile("||ads.example.net^", "@@||ads.example.net/ok^")
        assertEquals("||ads.example.net^", e.filterText(e.matchFilter("https://ads.example.net/1", "ads.example.net",
            "https://example.com/", "example.com", ResourceType.SCRIPT)))
        assertEquals("@@||ads.example.net/ok^", e.filterText(e.matchFilter("https://ads.example.net/ok/1", "ads.example.net",
            "https://example.com/", "example.com", ResourceType.SCRIPT)))
    }
}
