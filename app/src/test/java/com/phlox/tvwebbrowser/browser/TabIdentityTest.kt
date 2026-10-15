package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.model.WebTabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tab is the same tab whatever its fields are.
 */
class TabIdentityTest {
    @Test
    fun tabsWithTheSameFieldsAreDifferentTabs() {
        val a = WebTabState(url = "https://a.com/")
        val b = WebTabState(url = "https://a.com/")
        assertNotEquals(a, b)
        assertEquals(1, listOf(a, b).indexOf(b))
        val list = mutableListOf(a, b)
        list.remove(b)
        assertTrue(list.single() === a)
    }

    @Test
    fun changedTabIsStillFoundInHashSets() {
        val tab = WebTabState(url = "https://a.com/")
        val set = hashSetOf(tab)
        tab.url = "https://b.com/"
        tab.id = 5
        tab.position = 3
        assertTrue(tab in set)
    }
}
