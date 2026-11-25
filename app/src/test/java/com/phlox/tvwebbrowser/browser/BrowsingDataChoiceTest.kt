package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.browser.BrowsingDataKind.BOOKMARKS
import com.phlox.tvwebbrowser.browser.BrowsingDataKind.CACHE
import com.phlox.tvwebbrowser.browser.BrowsingDataKind.DOWNLOADS
import com.phlox.tvwebbrowser.browser.BrowsingDataKind.HISTORY
import com.phlox.tvwebbrowser.browser.BrowsingDataKind.SITE_DATA
import com.phlox.tvwebbrowser.browser.BrowsingDataKind.SITE_SETTINGS
import com.phlox.tvwebbrowser.browser.BrowsingDataKind.TABS
import com.phlox.tvwebbrowser.webengine.DataClearing
import com.phlox.tvwebbrowser.webengine.DataProfile
import com.phlox.tvwebbrowser.webengine.DataProfile.INCOGNITO
import com.phlox.tvwebbrowser.webengine.DataProfile.KIDS
import com.phlox.tvwebbrowser.webengine.DataProfile.MAIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsingDataChoiceTest {
    /** WebView: each profile apart */
    private val separate: (DataProfile) -> DataClearing = { DataClearing.SEPARATE }

    /** GeckoView: the main and kids profiles together, incognito not at all */
    private val together: (DataProfile) -> DataClearing = {
        if (it == INCOGNITO) DataClearing.NONE else DataClearing.WITH_OTHERS
    }

    @Test
    fun startsWithHistoryAndCacheOfTheMainProfile() {
        val choice = BrowsingDataChoice(listOf(MAIN, KIDS), separate, null)
        assertEquals(mapOf(MAIN to setOf(HISTORY, CACHE)), choice.chosen())
        assertFalse(choice.isChecked(MAIN, SITE_DATA))
        assertFalse(choice.hasLinks)
    }

    @Test
    fun incognitoHasOnlyItsTabsAndThePagesData() {
        val choice = BrowsingDataChoice(listOf(MAIN, INCOGNITO), separate, null)
        //its history, bookmarks, downloads and site settings are of the main profile
        for (kind in listOf(HISTORY, BOOKMARKS, DOWNLOADS, SITE_SETTINGS)) assertFalse(choice.isEnabled(INCOGNITO, kind))
        assertTrue(choice.isEnabled(INCOGNITO, TABS))
        assertTrue(choice.isEnabled(INCOGNITO, SITE_DATA))
        choice.toggle(INCOGNITO, HISTORY)
        assertFalse(choice.isChecked(INCOGNITO, HISTORY))
        //the kids profile has all of them
        val kids = BrowsingDataChoice(listOf(MAIN, KIDS), separate, null)
        assertTrue(BrowsingDataKind.entries.all { kids.isEnabled(KIDS, it) })
    }

    @Test
    fun separateProfilesAreCheckedApart() {
        val choice = BrowsingDataChoice(listOf(MAIN, KIDS), separate, emptySet())
        assertFalse(choice.anyChecked)
        choice.toggle(KIDS, SITE_DATA)
        assertEquals(mapOf(KIDS to setOf(SITE_DATA)), choice.chosen())
    }

    @Test
    fun profilesClearedTogetherAreCheckedTogether() {
        val choice = BrowsingDataChoice(listOf(MAIN, KIDS, INCOGNITO), together, emptySet())
        assertTrue(choice.hasLinks)
        assertTrue(choice.linked(MAIN, SITE_DATA))
        //the history of each profile is apart
        assertFalse(choice.linked(MAIN, HISTORY))
        assertFalse(choice.isEnabled(INCOGNITO, SITE_DATA))
        assertFalse(choice.isEnabled(INCOGNITO, CACHE))

        choice.toggle(KIDS, SITE_DATA)
        assertTrue(choice.isChecked(MAIN, SITE_DATA))
        assertTrue(choice.isChecked(KIDS, SITE_DATA))
        assertFalse(choice.isChecked(INCOGNITO, SITE_DATA))
        choice.toggle(MAIN, SITE_DATA)
        assertFalse(choice.isChecked(KIDS, SITE_DATA))

        choice.toggle(KIDS, HISTORY)
        choice.toggle(KIDS, SITE_SETTINGS)
        assertEquals(mapOf(KIDS to setOf(HISTORY, SITE_SETTINGS)), choice.chosen())
        assertFalse(choice.linked(KIDS, SITE_SETTINGS))
    }

    @Test
    fun aRememberedBoxChecksTheOnesClearedWithIt() {
        //the cache of the main profile, remembered while the kids profile had no data
        val choice = BrowsingDataChoice(listOf(MAIN, KIDS), together, null)
        assertTrue(choice.isChecked(KIDS, CACHE))
        assertFalse(choice.isChecked(KIDS, HISTORY))
    }

    @Test
    fun boxesOfProfilesNotShownAreKept() {
        val saved = setOf("KIDS:HISTORY", "MAIN:CACHE")
        val choice = BrowsingDataChoice(listOf(MAIN), separate, saved)
        assertEquals(mapOf(MAIN to setOf(CACHE)), choice.chosen())
        choice.toggle(MAIN, SITE_DATA)
        assertEquals(setOf("KIDS:HISTORY", "MAIN:CACHE", "MAIN:SITE_DATA"), choice.remembered())
        //and come back when the profile is shown
        val again = BrowsingDataChoice(listOf(MAIN, KIDS), separate, choice.remembered())
        assertTrue(again.isChecked(KIDS, HISTORY))
    }
}
