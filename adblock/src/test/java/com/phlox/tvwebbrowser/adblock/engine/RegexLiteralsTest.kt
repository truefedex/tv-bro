package com.phlox.tvwebbrowser.adblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegexLiteralsTest {
    @Test
    fun literalAfterAGroup() {
        assertEquals("104.154.", RegexLiterals.required("(https?:\\/\\/)104\\.154\\..{100,}"))
    }

    @Test
    fun quantifiedCharactersAreLeftOut() {
        //"s3." loses its dot to the '*'
        assertEquals(".amazonaws.com/", RegexLiterals.required("^https?:\\/\\/s3\\.*\\.amazonaws\\.com\\/[a-f0-9]{45,}"))
        assertEquals("/invoke.js", RegexLiterals.required("\\/[0-9a-f]{32}\\/invoke\\.js"))
    }

    @Test
    fun classesAndEscapedLettersEndRuns() {
        assertEquals("banner", RegexLiterals.required("banner\\d+\\.gif"))
    }

    @Test
    fun noLiteralWithTopLevelAlternation() {
        assertNull(RegexLiterals.required("ads\\.example|tracker\\.example"))
    }

    @Test
    fun alternationInsideAGroupKeepsTheRest() {
        assertEquals(".example.com/", RegexLiterals.required("(ads|track)\\.example\\.com\\/"))
    }

    @Test
    fun tooShortLiterals() {
        assertNull(RegexLiterals.required("a\\d+b"))
        assertNull(RegexLiterals.required("[a-z]+"))
    }
}
