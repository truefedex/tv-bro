package com.phlox.tvwebbrowser.adblock.psl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class PublicSuffixListTest {
    companion object {
        private val moduleDir = File(System.getProperty("adblock.moduleDir") ?: ".")
        private val listFile = File(moduleDir, "psl/public_suffix_list.dat")
        private val assetFile = File(moduleDir, "src/main/assets/adblock/psl.txt")
        private lateinit var psl: PublicSuffixList

        @BeforeClass
        @JvmStatic
        fun load() {
            //-PupdatePsl writes the asset from the list, for a new version of the list
            if (System.getProperty("adblock.updatePsl") == "true") {
                assetFile.parentFile.mkdirs()
                assetFile.writeBytes(PublicSuffixListCompiler.compile(listFile.readText()))
            }
            psl = PublicSuffixList.load(assetFile.readBytes())
        }
    }

    @Test
    fun assetIsCompiledFromTheList() {
        val compiled = PublicSuffixListCompiler.compile(listFile.readText())
        assertArrayEquals("the asset is older than psl/public_suffix_list.dat: run the tests with -PupdatePsl",
            compiled, assetFile.readBytes())
        assertTrue(psl.version, psl.version.isNotEmpty())
        assertTrue(psl.ruleCount > 9000)
    }

    private fun site(host: String) = psl.registrableDomain(host)

    @Test
    fun plainRules() {
        assertEquals("example.com", site("example.com"))
        assertEquals("example.com", site("a.b.www.example.com"))
        assertEquals("example.co.uk", site("www.example.co.uk"))
        assertEquals("bbc.co.uk", site("static.files.bbc.co.uk"))
    }

    @Test
    fun publicSuffixesAreTheirOwnSite() {
        assertEquals("com", site("com"))
        assertEquals("co.uk", site("co.uk"))
        assertEquals(0, psl.registrableDomainStart("co.uk"))
        assertEquals(0, psl.publicSuffixStart("co.uk"))
    }

    @Test
    fun wildcardAndExceptionRules() {
        //*.ck and !www.ck
        assertEquals("foo.bar.ck", site("foo.bar.ck"))
        assertEquals("bar.ck", site("bar.ck"))
        assertEquals("www.ck", site("www.ck"))
        assertEquals("www.ck", site("a.b.www.ck"))
        assertEquals(4, psl.publicSuffixStart("www.ck"))
    }

    @Test
    fun privateDomainsAreSuffixes() {
        assertEquals("user.github.io", site("user.github.io"))
        assertEquals("user.github.io", site("cdn.user.github.io"))
        assertEquals("github.io", site("github.io"))
        assertEquals("blog.blogspot.com", site("www.blog.blogspot.com"))
    }

    @Test
    fun internationalDomainsInPunycode() {
        //пример.рф
        assertEquals("xn--e1afmkfd.xn--p1ai", site("www.xn--e1afmkfd.xn--p1ai"))
        assertEquals("xn--p1ai", site("xn--p1ai"))
    }

    @Test
    fun unknownTopLevelDomainIsTheSuffix() {
        assertEquals("example.unknowntld", site("a.example.unknowntld"))
    }

    @Test
    fun ipAddressesAndSingleLabels() {
        assertEquals("192.168.0.1", site("192.168.0.1"))
        assertEquals("[::1]", site("[::1]"))
        assertEquals("localhost", site("localhost"))
    }

    @Test
    fun sameSite() {
        assertTrue(psl.isSameSite("ads.example.com", "www.example.com"))
        assertTrue(psl.isSameSite("example.com", "cdn.example.com"))
        assertFalse(psl.isSameSite("example.com", "example.org"))
        assertFalse(psl.isSameSite("example.com", "notexample.com"))
        assertFalse(psl.isSameSite("alice.github.io", "bob.github.io"))
        assertTrue(psl.isSameSite("192.168.0.1", "192.168.0.1"))
        assertFalse(psl.isSameSite("192.168.0.1", "192.168.0.2"))
    }

    @Test
    fun loadsFileWithWindowsLineEnds() {
        val crlf = String(assetFile.readBytes(), Charsets.US_ASCII).replace("\n", "\r\n").toByteArray()
        val converted = PublicSuffixList.load(crlf)
        assertEquals(psl.version, converted.version)
        assertEquals("example.co.uk", converted.registrableDomain("www.example.co.uk"))
        assertEquals("www.ck", converted.registrableDomain("a.www.ck"))
    }

    @Test
    fun loadRejectsOtherFiles() {
        for (bad in listOf("", "hello\n", "PSL1 x\n3\ncom\n")) {
            try {
                PublicSuffixList.load(bad.toByteArray())
                throw AssertionError("loaded: $bad")
            } catch (e: IllegalArgumentException) {
                //expected
            }
        }
    }
}
