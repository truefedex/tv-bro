package com.phlox.tvwebbrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UpdateChecker.compareVersions], which drives the GitHub-releases update check
 * (release tags are dotted versions like "v2.1.7" with no numeric versionCode).
 */
class UpdateCheckerVersionTest {

    private fun sign(value: Int) = if (value > 0) 1 else if (value < 0) -1 else 0

    @Test
    fun equalVersionsIgnoringLeadingV() {
        assertEquals(0, UpdateChecker.compareVersions("v2.1.6", "2.1.6"))
    }

    @Test
    fun numericComparisonNotLexicographic() {
        assertTrue(UpdateChecker.compareVersions("v2.1.10", "v2.1.9") > 0)
        assertTrue(UpdateChecker.compareVersions("v2.1.9", "v2.1.10") < 0)
    }

    @Test
    fun majorAndMinorTakePrecedence() {
        assertTrue(UpdateChecker.compareVersions("v2.2.0", "v2.1.9") > 0)
        assertTrue(UpdateChecker.compareVersions("v3.0.0", "v2.99.99") > 0)
    }

    @Test
    fun differentComponentCountsAreCompared() {
        assertTrue(UpdateChecker.compareVersions("2.2", "2.1.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("2.1.0", "2.1"))
    }

    @Test
    fun suffixIsIgnoredButDigitsBeforeItCount() {
        //prerelease/build suffixes must not make a version look older than a released one
        assertEquals(1, sign(UpdateChecker.compareVersions("2.1.6-beta", "2.1.5")))
        assertEquals(0, sign(UpdateChecker.compareVersions("2.1.6-beta", "2.1.6")))
    }

    @Test
    fun emptyVersionIsNeverNewer() {
        //no matching release is reported as an empty version name and must not trigger an update
        assertTrue(UpdateChecker.compareVersions("", "2.1.6") < 0)
    }
}
