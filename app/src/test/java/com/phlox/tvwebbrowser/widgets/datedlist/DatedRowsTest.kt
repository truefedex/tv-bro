package com.phlox.tvwebbrowser.widgets.datedlist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DatedRowsTest {
    private data class Entry(val name: String, val time: Long)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().run {
        clear()
        set(2026, Calendar.OCTOBER, day, hour, minute)
        timeInMillis
    }

    private val late15 = Entry("a", at(15, 23, 59))
    private val early15 = Entry("b", at(15, 0, 0))
    private val late14 = Entry("c", at(14, 23, 59))
    private val noon13 = Entry("d", at(13, 12))

    private fun rows(vararg entries: Entry) = buildDatedRows(entries.toList()) { it.time }

    @Test
    fun eachDayStartsWithItsHeader() {
        assertEquals(listOf(
            DatedRow.Header(startOfDay(late15.time)), DatedRow.Item(late15), DatedRow.Item(early15),
            DatedRow.Header(startOfDay(late14.time)), DatedRow.Item(late14),
            DatedRow.Header(startOfDay(noon13.time)), DatedRow.Item(noon13),
        ), rows(late15, early15, late14, noon13))
    }

    @Test
    fun headersFollowTheLocalDay() {
        assertEquals(at(15, 0), startOfDay(late15.time))
        assertEquals(at(15, 0), startOfDay(early15.time))
        assertEquals(at(14, 0), startOfDay(late14.time))
    }

    @Test
    fun noHeaderIsLeftForADayWithoutItems() {
        //the items of the 14th are deleted
        assertEquals(listOf(
            DatedRow.Header(startOfDay(late15.time)), DatedRow.Item(late15),
            DatedRow.Header(startOfDay(noon13.time)), DatedRow.Item(noon13),
        ), rows(late15, noon13))
        assertEquals(emptyList<DatedRow<Entry>>(), rows())
    }

    @Test
    fun aListBuiltAgainGetsItsFirstHeader() {
        //like the results of a search shown after the list was cleared: they start with a header
        rows(late15, early15)
        assertEquals(DatedRow.Header(startOfDay(late15.time)), rows(late15).first())
    }

    @Test
    fun headerPositionOfEachRow() {
        val rows = rows(late15, early15, late14, noon13)
        assertArrayEquals(intArrayOf(0, 0, 0, 3, 3, 5, 5), headerPositions(rows))
        assertArrayEquals(intArrayOf(), headerPositions(emptyList()))
    }
}
