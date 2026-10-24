package com.phlox.tvwebbrowser.adblock.parser

import com.phlox.tvwebbrowser.adblock.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterParserTest {
    private val parser = FilterParser()

    private fun network(line: String) = parser.parse(line) as NetworkFilter
    private fun cosmetic(line: String) = parser.parse(line) as CosmeticFilter

    /** Parses the [line] with a new parser and returns the reason it was skipped */
    private fun skipReason(line: String): String {
        val parser = FilterParser()
        assertNull(line, parser.parse(line))
        assertEquals(line, 1, parser.stats.skippedTotal)
        return parser.stats.skipped.keys.single()
    }

    // --- Comments and header ---

    @Test
    fun commentsAndHeaderAreNotFilters() {
        val lines = listOf("[Adblock Plus 2.0]", "! Title: EasyList", "! Version: 202610240652",
            "! Expires: 4 days (update frequency)", "! comment ## not a filter", "", "   ", "# a comment of uBO")
        lines.forEach { assertNull(it, parser.parse(it)) }
        assertEquals(lines.size, parser.stats.comments)
        assertEquals(0, parser.stats.network + parser.stats.cosmetic + parser.stats.skippedTotal)
        assertEquals("EasyList", parser.stats.title)
        assertEquals("202610240652", parser.stats.version)
        assertEquals(96, parser.stats.expiresHours)
    }

    @Test
    fun expiresInHours() {
        parser.parse("! Expires: 12 hours")
        assertEquals(12, parser.stats.expiresHours)
    }

    // --- Patterns and anchors ---

    @Test
    fun plainPatternIsLowercasedWithoutAnchors() {
        val f = network("/Banner/Ads-")
        assertEquals("/banner/ads-", f.pattern)
        assertFalse(f.anchorHost || f.anchorStart || f.anchorEnd || f.isRegex || f.isException || f.isHostOnly)
        assertEquals(ResourceType.DEFAULT, f.types)
        assertNull(f.thirdParty)
    }

    @Test
    fun hostAnchorWithSeparatorIsHostOnly() {
        val f = network("||Ads.Example.com^")
        assertTrue(f.anchorHost && f.isHostOnly)
        assertEquals("ads.example.com", f.pattern)
    }

    @Test
    fun hostAnchorWithPathIsNotHostOnly() {
        val f = network("||example.com/ads/*.js")
        assertTrue(f.anchorHost)
        assertFalse(f.isHostOnly)
        assertEquals("example.com/ads/*.js", f.pattern)
    }

    @Test
    fun hostAnchorWithoutSeparatorIsNotHostOnly() {
        //it also matches example.community
        assertFalse(network("||example.com").isHostOnly)
    }

    @Test
    fun startAndEndAnchors() {
        val f = network("|https://ads.example.com/banner.gif|")
        assertTrue(f.anchorStart && f.anchorEnd)
        assertFalse(f.anchorHost)
        assertEquals("https://ads.example.com/banner.gif", f.pattern)
    }

    @Test
    fun starsAreCollapsedAndDropTheAnchorsOfTheirEnd() {
        val f = network("|*ads**banner*|")
        assertEquals("ads*banner", f.pattern)
        assertFalse(f.anchorStart || f.anchorEnd)
    }

    @Test
    fun internationalHostIsPunycode() {
        val f = network("||пример.рф^")
        assertTrue(f.isHostOnly)
        assertEquals("xn--e1afmkfd.xn--p1ai", f.pattern)
    }

    @Test
    fun matchCaseKeepsTheCase() {
        val f = network("/BannerAd.\$match-case")
        assertTrue(f.matchCase)
        assertEquals("/BannerAd.", f.pattern)
    }

    // --- Regular expressions ---

    @Test
    fun regexWithItsOwnDollar() {
        val f = network("/ads\\d+\\.gif$/")
        assertTrue(f.isRegex)
        assertEquals("ads\\d+\\.gif$", f.pattern)
        assertEquals(ResourceType.DEFAULT, f.types)
    }

    @Test
    fun regexWithOptions() {
        val f = network("/banner[0-9]+\\.js$/\$script,third-party")
        assertTrue(f.isRegex)
        assertEquals("banner[0-9]+\\.js$", f.pattern)
        assertEquals(ResourceType.SCRIPT, f.types)
        assertEquals(true, f.thirdParty)
    }

    @Test
    fun pathStartingWithSlashIsNotRegexBeforeOptions() {
        val f = network("/ad-frame/*\$subdocument")
        assertFalse(f.isRegex)
        assertEquals("/ad-frame/", f.pattern)
        assertEquals(ResourceType.SUBDOCUMENT, f.types)
    }

    @Test
    fun invalidRegexIsSkipped() {
        assertEquals(SkipReason.INVALID_REGEX, skipReason("/ads(\\d+/"))
    }

    // --- Options ---

    @Test
    fun exceptionWithTypes() {
        val f = network("@@||cdn.example.com^\$script,stylesheet")
        assertTrue(f.isException && f.isHostOnly)
        assertEquals(ResourceType.SCRIPT or ResourceType.STYLESHEET, f.types)
    }

    @Test
    fun negatedTypesAreRemovedFromTheDefault() {
        val f = network("||ads.example.com^\$~image,~script")
        assertEquals(ResourceType.DEFAULT and (ResourceType.IMAGE or ResourceType.SCRIPT).inv(), f.types)
        assertEquals(0, f.types and ResourceType.DOCUMENT)
    }

    @Test
    fun typeAliasesOfUBlockOrigin() {
        assertEquals(ResourceType.XHR or ResourceType.STYLESHEET or ResourceType.SUBDOCUMENT,
            network("||a.example.com^\$xhr,css,frame").types)
        assertEquals(ResourceType.PING, network("||b.example.com^\$beacon").types)
    }

    @Test
    fun thirdPartyAndItsAliases() {
        assertEquals(true, network("||a.example.com^\$third-party").thirdParty)
        assertEquals(false, network("||a.example.com^\$~third-party").thirdParty)
        assertEquals(true, network("||a.example.com^\$3p").thirdParty)
        assertEquals(false, network("||a.example.com^\$1p").thirdParty)
        assertEquals(true, network("||a.example.com^\$~first-party").thirdParty)
    }

    @Test
    fun domainOption() {
        val f = network("/ads.js\$domain=Example.com|~sub.example.com|пример.рф")
        assertEquals(listOf("example.com", "xn--e1afmkfd.xn--p1ai"), f.domains!!.include)
        assertEquals(listOf("sub.example.com"), f.domains!!.exclude)
    }

    @Test
    fun emptyPatternWithDomainIsKept() {
        val f = network("\$script,third-party,domain=example.com")
        assertEquals("", f.pattern)
        assertEquals(ResourceType.SCRIPT, f.types)
    }

    @Test
    fun importantAndDocument() {
        val f = network("||ads.example.com^\$important")
        assertTrue(f.important)
        val page = network("@@||example.com^\$document")
        assertTrue(page.isException)
        assertEquals(ResourceType.DOCUMENT, page.types)
        assertEquals(ResourceType.DOCUMENT, network("||bad.example.com^\$doc").types)
    }

    @Test
    fun elementHidingExceptionsDoNotLetRequestsThrough() {
        val f = network("@@||example.com^\$elemhide")
        assertTrue(f.elemHide)
        assertEquals(0, f.types)
        val g = network("@@||example.com^\$generichide")
        assertTrue(g.genericHide)
        assertEquals(0, g.types)
        assertTrue(network("@@||example.com^\$ghide").genericHide)
    }

    @Test
    fun elementHidingOptionsNeedAnException() {
        assertEquals("option:elemhide", skipReason("||example.com^\$elemhide"))
    }

    @Test
    fun unknownOptionsSkipTheWholeFilter() {
        assertEquals("option:redirect", skipReason("||ads.example.com/ga.js\$script,redirect=google-analytics.com/ga.js"))
        assertEquals("option:csp", skipReason("||example.com^\$csp=script-src 'self'"))
        assertEquals("option:removeparam", skipReason("\$removeparam=utm_source"))
        assertEquals("option:popup", skipReason("||ads.example.com^\$popup"))
        assertEquals("option:badfilter", skipReason("||ads.example.com^\$badfilter"))
    }

    @Test
    fun negatedDocumentChangesNothing() {
        val f = network("/webtracking/*\$~document,~subdocument,domain=~example.com")
        assertEquals(ResourceType.DEFAULT and ResourceType.SUBDOCUMENT.inv(), f.types)
    }

    @Test
    fun filtersOnlyOfEntityDomainsAreSkipped() {
        assertEquals(SkipReason.ENTITY, skipReason("/ads.js\$domain=example.*"))
        assertEquals(SkipReason.ENTITY, skipReason("example.*##.ad"))
        assertEquals(SkipReason.ENTITY, skipReason("\$script,domain=/example\\.com/"))
    }

    @Test
    fun includedEntitiesAreLeftOutOfTheOtherDomains() {
        assertEquals(listOf("a.com"), network("||ads.example.com^\$domain=a.com|example.*").domains!!.include)
        assertEquals(listOf("a.com", "b.com"), cosmetic("a.com,example.*,b.com##.ad").domains!!.include)
    }

    @Test
    fun excludedEntitiesSkipTheFilter() {
        //without the exclusion the filter would apply on those sites
        assertEquals(SkipReason.ENTITY, skipReason("||ads.example.com^\$domain=a.com|~example.*"))
        assertEquals(SkipReason.ENTITY, skipReason("a.com,~example.*##.ad"))
    }

    @Test
    fun ipv6DomainsInOptions() {
        val f = network("||0.0.0.0^\$third-party,domain=~127.0.0.1|~[::1]|~localhost")
        assertEquals(listOf("127.0.0.1", "[::1]", "localhost"), f.domains!!.exclude)
    }

    @Test
    fun tooGenericFiltersAreSkipped() {
        assertEquals(SkipReason.TOO_GENERIC, skipReason("*"))
        assertEquals(SkipReason.TOO_GENERIC, skipReason("a^"))
        assertEquals(SkipReason.TOO_GENERIC, skipReason("\$third-party"))
        assertEquals(SkipReason.TOO_GENERIC, skipReason("*\$~image"))
        assertEquals(SkipReason.TOO_GENERIC, skipReason("\$document"))
        assertEquals(SkipReason.TOO_GENERIC, skipReason("@@\$generichide"))
    }

    @Test
    fun emptyPatternWithFewTypesIsKept() {
        val f = network("*\$ping,third-party")
        assertEquals("", f.pattern)
        assertEquals(ResourceType.PING, f.types)
        assertEquals(true, f.thirdParty)
    }

    @Test
    fun negatingEveryTypeIsInvalid() {
        val all = ResourceType.byOption.filterValues { it != ResourceType.DOCUMENT }.keys.joinToString(",") { "~$it" }
        assertEquals(SkipReason.INVALID, skipReason("||ads.example.com^\$$all"))
    }

    // --- Element hiding ---

    @Test
    fun genericElementHiding() {
        val f = cosmetic("##.ad-banner")
        assertEquals(".ad-banner", f.selector)
        assertFalse(f.isException)
        assertTrue(f.isGeneric)
        assertNull(f.domains)
    }

    @Test
    fun specificElementHidingWithExclusions() {
        val f = cosmetic("Example.com,~news.example.com,пример.рф##div[id^=\"ad-\"] > .banner")
        assertEquals("div[id^=\"ad-\"] > .banner", f.selector)
        assertEquals(listOf("example.com", "xn--e1afmkfd.xn--p1ai"), f.domains!!.include)
        assertEquals(listOf("news.example.com"), f.domains!!.exclude)
        assertFalse(f.isGeneric)
    }

    @Test
    fun genericWithExclusionsOnly() {
        val f = cosmetic("~example.com##.ad")
        assertTrue(f.isGeneric)
        assertEquals(listOf("example.com"), f.domains!!.exclude)
    }

    @Test
    fun elementHidingExceptions() {
        val f = cosmetic("example.com#@#.ad-banner")
        assertTrue(f.isException)
        assertEquals(".ad-banner", f.selector)
        assertTrue(cosmetic("#@#.sponsored").isException)
    }

    @Test
    fun hashInsideUrlPatternIsNotElementHiding() {
        val f = network("||example.com/#!/ads")
        assertEquals("example.com/#!/ads", f.pattern)
    }

    @Test
    fun unsupportedElementHidingIsSkipped() {
        assertEquals(SkipReason.SCRIPTLET, skipReason("example.com##+js(set-constant, adsEnabled, false)"))
        assertEquals(SkipReason.SCRIPTLET, skipReason("example.com#\$#abort-on-property-read adsbygoogle"))
        assertEquals(SkipReason.SCRIPTLET, skipReason("example.com#%#window.ads = false;"))
        assertEquals(SkipReason.PROCEDURAL, skipReason("example.com#?#div:-abp-has(> .ad)"))
        assertEquals(SkipReason.PROCEDURAL, skipReason("example.com##div:has(> a[href*=\"ads\"])"))
        assertEquals(SkipReason.PROCEDURAL, skipReason("example.com##.post:has-text(Sponsored)"))
        assertEquals(SkipReason.CSS_INJECTION, skipReason("example.com##body:style(overflow: auto !important)"))
        assertEquals(SkipReason.CSS_INJECTION, skipReason("example.com#\$?#body { overflow: auto }"))
        assertEquals(SkipReason.CSS_INJECTION, skipReason("example.com##.banner-spacer {max-height: 50px !important;}"))
        assertEquals(SkipReason.HTML, skipReason("example.com##^script:has-text(ads)"))
        assertEquals(SkipReason.HTML, skipReason("example.com\$\$script[data-ad]"))
    }

    @Test
    fun selectorsThatWouldBreakTheStylesheetAreSkipped() {
        assertEquals(SkipReason.INVALID_SELECTOR, skipReason("##.ad{color:red"))
        assertEquals(SkipReason.INVALID_SELECTOR, skipReason("##.ad}"))
        assertEquals(SkipReason.INVALID_SELECTOR, skipReason("##.ad /* comment"))
        assertEquals(SkipReason.INVALID_SELECTOR, skipReason("##@import url(x)"))
        assertEquals(SkipReason.INVALID_SELECTOR, skipReason("##.ad\\"))
        assertEquals(SkipReason.INVALID_SELECTOR, skipReason("example.com##"))
    }

    // --- Hosts files ---

    @Test
    fun hostsFileLines() {
        val f = network("0.0.0.0 Ads.Example.com # tracker")
        assertTrue(f.isHostOnly && f.anchorHost)
        assertEquals("ads.example.com", f.pattern)
        assertTrue(network("127.0.0.1\ttracker.example.net").isHostOnly)
        assertNull(parser.parse("127.0.0.1 localhost"))
    }

    // --- Stats ---

    @Test
    fun statsCountEveryLine() {
        val lines = listOf("! Title: Test", "||ads.example.com^", "##.ad", "example.com##+js(noop)",
            "||x.example.com^\$csp=default-src", "@@||example.com^\$document")
        val filters = ArrayList<Filter>()
        val stats = FilterParser().parseList(lines.asSequence()) { filters += it }
        assertEquals(6, stats.lines)
        assertEquals(1, stats.comments)
        assertEquals(2, stats.network)
        assertEquals(1, stats.cosmetic)
        assertEquals(2, stats.skippedTotal)
        assertEquals(mapOf("option:csp" to 1, SkipReason.SCRIPTLET to 1), stats.skipped)
        assertEquals(3, filters.size)
    }
}
