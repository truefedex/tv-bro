package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.activity.main.HistoryNavigator.Action
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator.Focus
import com.phlox.tvwebbrowser.activity.main.HistoryNavigator.Screen
import com.phlox.tvwebbrowser.model.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HistoryNavigatorTest {
    private val context = RuntimeEnvironment.getApplication()

    private val stored = arrayListOf(
        item(5, "Wikipedia, the free encyclopedia", "https://en.wikipedia.org/wiki/Main_Page"),
        item(4, "Television — Вікіпедія", "https://uk.wikipedia.org/wiki/Television"),
        item(3, "YouTube", "https://www.youtube.com/"),
        item(2, "", "https://example.com/page"),
        item(1, "Kotlin coroutines", "https://github.com/Kotlin/kotlinx.coroutines/wiki")
    )
    private val deleted = ArrayList<Long>()
    private var deletedAll = false

    private lateinit var screen: Screen
    private var focus: Focus? = null
    private var renders = 0
    private val log = ArrayList<String>()
    private val messages = ArrayList<String>()
    private var confirmation: String? = null
    private var confirmAnswer = true

    private val store = object : HistoryNavigator.Store {
        override suspend fun all() = ArrayList(stored)
        override suspend fun delete(items: List<HistoryItem>) {
            deleted += items.map { it.id }
            stored.removeAll { it.id in deleted }
        }
        override suspend fun deleteAll() {
            deletedAll = true
            stored.clear()
        }
    }

    private val navigator = HistoryNavigator(context, store, CoroutineScope(Dispatchers.Unconfined), object : HistoryNavigator.Host {
        override fun render(screen: Screen, focus: Focus?) {
            this@HistoryNavigatorTest.screen = screen
            this@HistoryNavigatorTest.focus = focus
            renders++
        }
        override fun open(url: String) { log += "open $url" }
        override fun openInNewTab(url: String) { log += "new tab $url" }
        override fun copyLink(url: String) { log += "copy $url" }
        override fun showMessage(icon: Int, message: String) { messages += message }
        override fun confirm(message: String, action: () -> Unit) {
            confirmation = message
            if (confirmAnswer) action()
        }
    }, searchDelay = 0, searchContext = EmptyCoroutineContext)

    private fun item(id: Long, title: String, url: String) = HistoryItem().apply {
        this.id = id
        this.title = title
        this.url = url
        time = id * 1000
    }

    private val shownIds get() = screen.items.map { it.id }
    private fun shown(id: Long) = screen.items.first { it.id == id }

    @Test
    fun startShowsAllTheHistoryWithTheFirstRowFocused() {
        navigator.start()
        assertTrue(screen.loaded)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), shownIds)
        assertEquals(5, screen.total)
        assertNull(focus!!.key)
        assertEquals(0, focus!!.index)
        assertFalse(screen.searching)
        assertFalse(screen.selecting)
    }

    @Test
    fun searchFindsEachWordInTheTitleOrTheUrlIgnoringCase() {
        navigator.start()
        navigator.search("WIKI")
        assertEquals(listOf(5L, 4L, 1L), shownIds)
        assertEquals(listOf("wiki"), screen.words.map { it.lowercase() })
        //the focus stays in the field
        assertNull(focus)

        navigator.search("wiki television")
        assertEquals(listOf(4L), shownIds)

        //case folding outside ASCII, which SQLite's LIKE does not do
        navigator.search("вікі")
        assertEquals(listOf(4L), shownIds)

        navigator.search("  ")
        assertEquals(5, screen.items.size)
        assertFalse(screen.searching)
    }

    @Test
    fun searchTypedBeforeTheHistoryIsLoadedApplies() {
        val slowStore = object : HistoryNavigator.Store by store {
            var load: (() -> Unit)? = null
            override suspend fun all(): List<HistoryItem> = kotlinx.coroutines.suspendCancellableCoroutine { c ->
                load = { c.resumeWith(Result.success(ArrayList(stored))) }
            }
        }
        val navigator = HistoryNavigator(context, slowStore, CoroutineScope(Dispatchers.Unconfined), object : HistoryNavigator.Host {
            override fun render(screen: Screen, focus: Focus?) { this@HistoryNavigatorTest.screen = screen }
            override fun open(url: String) {}
            override fun openInNewTab(url: String) {}
            override fun copyLink(url: String) {}
            override fun showMessage(icon: Int, message: String) {}
            override fun confirm(message: String, action: () -> Unit) {}
        }, searchDelay = 0, searchContext = EmptyCoroutineContext)
        navigator.start()
        assertFalse(screen.loaded)
        navigator.search("youtube")
        slowStore.load!!()
        assertTrue(screen.loaded)
        assertEquals(listOf(3L), shownIds)
    }

    @Test
    fun rowOpensItsPage() {
        navigator.start()
        navigator.onRowClick(shown(3))
        assertEquals(listOf("open https://www.youtube.com/"), log)
    }

    @Test
    fun actionsOfARow() {
        navigator.start()
        assertEquals(listOf(Action.OPEN_IN_NEW_TAB, Action.COPY_LINK, Action.SELECT, Action.DELETE), navigator.actionsOf(shown(3)))
        navigator.perform(shown(3), Action.OPEN_IN_NEW_TAB)
        navigator.perform(shown(3), Action.COPY_LINK)
        assertEquals(listOf("new tab https://www.youtube.com/", "copy https://www.youtube.com/"), log)
        assertEquals(listOf(context.getString(com.phlox.tvwebbrowser.R.string.link_copied)), messages)
    }

    @Test
    fun deletingARowFocusesTheNextOne() {
        navigator.start()
        navigator.perform(shown(3), Action.DELETE)
        assertEquals(listOf(5L, 4L, 2L, 1L), shownIds)
        assertEquals(HistoryNavigator.keyOf(2), focus!!.key)
        assertEquals(listOf(3L), deleted)
        assertEquals(4, screen.total)

        //the last row: the one before it
        navigator.perform(shown(1), Action.DELETE)
        assertEquals(HistoryNavigator.keyOf(2), focus!!.key)
    }

    @Test
    fun selectionModeChoosesAndDeletesRows() {
        navigator.start()
        navigator.onRowLongClick(shown(4))
        assertTrue(screen.selecting)
        assertEquals(setOf(4L), screen.selection)
        assertEquals(HistoryNavigator.keyOf(4), focus!!.key)

        //a tap chooses instead of opening
        navigator.onRowClick(shown(2))
        navigator.onRowClick(shown(4))
        assertEquals(setOf(2L), screen.selection)
        assertTrue(log.isEmpty())

        navigator.onRowLongClick(shown(1))
        assertEquals(setOf(2L, 1L), screen.selection)

        navigator.deleteSelected()
        assertEquals(context.resources.getQuantityString(com.phlox.tvwebbrowser.R.plurals.delete_history_items, 2, 2), confirmation)
        assertEquals(listOf(5L, 4L, 3L), shownIds)
        assertEquals(setOf(2L, 1L), deleted.toSet())
        assertFalse(screen.selecting)
        assertEquals(HistoryNavigator.keyOf(3), focus!!.key)
    }

    @Test
    fun deleteIsAskedFirst() {
        confirmAnswer = false
        navigator.start()
        navigator.startSelection(shown(5))
        navigator.deleteSelected()
        assertTrue(deleted.isEmpty())
        assertTrue(screen.selecting)
    }

    @Test
    fun selectAllChoosesTheItemsShownOrNone() {
        navigator.start()
        navigator.search("wiki")
        navigator.startSelection(shown(5))
        navigator.selectAll()
        assertEquals(setOf(5L, 4L, 1L), screen.selection)
        navigator.selectAll()
        assertEquals(emptySet<Long>(), screen.selection)
        navigator.deleteSelected()
        assertNull(confirmation)
        assertEquals(1, messages.size)
    }

    @Test
    fun backLeavesTheSelectionModeFirst() {
        navigator.start()
        navigator.startSelection(shown(5))
        assertTrue(navigator.onBack())
        assertFalse(screen.selecting)
        assertFalse(navigator.onBack())
    }

    @Test
    fun clearAllDeletesEverythingAfterConfirmation() {
        navigator.start()
        navigator.clearAll()
        assertEquals(context.getString(com.phlox.tvwebbrowser.R.string.msg_delete_history_all), confirmation)
        assertTrue(deletedAll)
        assertTrue(screen.items.isEmpty())
        assertEquals(0, screen.total)

        confirmation = null
        navigator.clearAll()
        assertNull(confirmation)
        assertEquals(listOf(context.getString(com.phlox.tvwebbrowser.R.string.history_is_empty)), messages)
    }

    @Test
    fun stopForgetsTheSearchAndTheSelection() {
        navigator.start()
        navigator.search("wiki")
        navigator.startSelection(shown(5))
        navigator.stop()
        navigator.start()
        assertFalse(screen.searching)
        assertFalse(screen.selecting)
        assertEquals("", navigator.currentQuery)
        assertEquals(5, screen.items.size)
    }

    @Test
    fun highlightsAreMergedAndSorted() {
        assertEquals(listOf(0..3, 10..13), HistoryNavigator.highlights("Wiki page wiki", listOf("wiki")))
        assertEquals(listOf(0..6), HistoryNavigator.highlights("Wikipedia", listOf("wiki", "kiped")))
        assertTrue(HistoryNavigator.highlights("YouTube", listOf("wiki")).isEmpty())
    }

    @Test
    fun addressShowsTheWholeUrlWhenTheMatchIsOutsideTheHost() {
        val item = item(1, "Kotlin coroutines", "https://github.com/Kotlin/kotlinx.coroutines/wiki")
        assertEquals("github.com", HistoryNavigator.addressOf(item, emptyList()))
        assertEquals("github.com", HistoryNavigator.addressOf(item, listOf("kotlin", "git")))
        assertEquals("github.com/Kotlin/kotlinx.coroutines/wiki", HistoryNavigator.addressOf(item, listOf("wiki")))
    }
}
