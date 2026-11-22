package com.phlox.tvwebbrowser.adblock.sitefilter

import com.phlox.tvwebbrowser.adblock.AdblockFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteFilterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val adult = 0
    private val gambling = 1
    private val all = (1L shl adult) or (1L shl gambling)

    private fun filter(): SiteFilter {
        val compiler = SiteFilterCompiler(2)
        compiler.add(adult, sequenceOf("# Title: adult", "adult.example", "*.wild.example", "0.0.0.0 hosts.example"))
        compiler.add(gambling, sequenceOf("||casino.example^", "bet.example", "adult.example"))
        return compiler.build(fingerprint = 42)
    }

    @Test
    fun linesOfAllFormatsAreRead() {
        assertEquals("example.com", DomainListParser.parseLine("example.com"))
        assertEquals("example.com", DomainListParser.parseLine("  Example.COM.  "))
        assertEquals("example.com", DomainListParser.parseLine("*.example.com"))
        assertEquals("example.com", DomainListParser.parseLine("0.0.0.0 example.com"))
        assertEquals("example.com", DomainListParser.parseLine("127.0.0.1\texample.com # a comment"))
        assertEquals("example.com", DomainListParser.parseLine("||example.com^"))
        assertEquals("xn--80ak6aa92e.com", DomainListParser.parseLine("xn--80ak6aa92e.com"))
        for (skipped in listOf("", "# Title: x", "! Expires: 1 day", "[Adblock Plus]", "1.2.3.4", "::1",
                "0.0.0.0 localhost", "||example.com^\$third-party", "/ads/", "приклад.укр", "a b c")) {
            assertNull(skipped, DomainListParser.parseLine(skipped))
        }
    }

    @Test
    fun aDomainCoversItsSubdomainsOnly() {
        val filter = filter()
        assertEquals(adult, filter.match("adult.example", all))
        assertEquals(adult, filter.match("www.adult.example", all))
        assertEquals(adult, filter.match("a.b.ADULT.example.", all))
        assertEquals(adult, filter.match("cdn.wild.example", all))
        assertEquals(adult, filter.match("hosts.example", all))
        assertEquals(gambling, filter.match("m.casino.example", all))
        assertEquals(-1, filter.match("notadult.example", all))
        assertEquals(-1, filter.match("example", all))
        assertEquals(-1, filter.match("", all))
    }

    @Test
    fun onlyTheEnabledCategoriesCount() {
        val filter = filter()
        //in both lists: the first enabled category
        assertEquals(gambling, filter.match("adult.example", 1L shl gambling))
        assertEquals(-1, filter.match("casino.example", 1L shl adult))
        assertEquals(-1, filter.match("adult.example", 0))
    }

    @Test
    fun theFileKeepsTheLists() {
        val file = temp.newFile("sites.bin")
        val built = filter()
        built.writeTo(file)
        val loaded = SiteFilter.load(file)
        assertEquals(42L, loaded.fingerprint)
        assertEquals(2, loaded.categoryCount)
        assertEquals(3, loaded.size(adult))
        //a domain twice in a list is kept once
        assertEquals(3, loaded.size(gambling))
        for (host in listOf("x.adult.example", "casino.example", "free.example")) {
            assertEquals(host, built.match(host, all), loaded.match(host, all))
        }
    }

    @Test(expected = AdblockFormatException::class)
    fun aDamagedFileIsRefused() {
        val file = temp.newFile("sites.bin")
        filter().writeTo(file)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)
        SiteFilter.load(file)
    }

    /** A list downloaded again replaces its category only: the others come from the filter as it is */
    @Test
    fun aCategoryIsReplacedOnTheFilterAsItIs() {
        val base = filter()
        assertEquals(true, base.has(adult))
        val compiler = SiteFilterCompiler(3, base)
        compiler.add(gambling, sequenceOf("poker.example"))
        val updated = compiler.build(fingerprint = 7)
        assertEquals(adult, updated.match("adult.example", 1L shl adult))
        assertEquals(gambling, updated.match("poker.example", all))
        assertEquals(-1, updated.match("casino.example", all))
        assertEquals(false, updated.has(2))
        val cleared = SiteFilterCompiler(3, updated).apply { clear(adult) }.build(fingerprint = 8)
        assertEquals(false, cleared.has(adult))
        assertEquals(-1, cleared.match("adult.example", all))
        assertEquals(gambling, cleared.match("poker.example", all))
        //what was built from a list stays known through the file
        val file = temp.newFile("updated.bin")
        updated.writeTo(file)
        assertEquals(true, SiteFilter.load(file).has(gambling))
        assertEquals(false, SiteFilter.load(file).has(2))
    }
}
