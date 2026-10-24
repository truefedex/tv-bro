package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Action
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Focus
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Kind
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Look
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator.Screen
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.model.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DownloadsNavigatorTest {
    private val context = RuntimeEnvironment.getApplication()
    private val downloads = MutableStateFlow<List<DownloadItem>>(emptyList())

    private lateinit var screen: Screen
    private var focus: Focus? = null
    private val messages = ArrayList<String>()
    private var shownAll: Long? = null
    private var clearAllAnswer: Boolean? = null
    private var clearAllRequest: DownloadsNavigator.ClearAllRequest? = null
    private val commandsLog = ArrayList<String>()
    private val screenLog = ArrayList<String>()
    private val deletedFiles = ArrayList<Long>()
    private var hasFolderApp = true
    private var hasShareApp = true

    private val commands = object : DownloadsNavigator.Commands {
        override fun pause(id: Long) { commandsLog += "pause $id" }
        override fun resume(item: DownloadItem) { commandsLog += "resume ${item.id}" }
        override fun cancel(id: Long) { commandsLog += "cancel $id" }
        override fun downloadAgain(item: DownloadItem) { commandsLog += "again ${item.id}" }
        override suspend fun remove(ids: List<Long>) {
            downloads.value = downloads.value.filter { it.id !in ids }
        }
        override fun onScreenShown(shown: Boolean) { screenLog += if (shown) "shown" else "closed" }
    }

    private val device = object : DownloadsNavigator.Device {
        override fun fileExists(item: DownloadItem) = item.filepath.isNotEmpty()
        override fun open(item: DownloadItem) = fileExists(item).also { if (it) commandsLog += "open ${item.id}" }
        override fun install(item: DownloadItem) = fileExists(item).also { if (it) commandsLog += "install ${item.id}" }
        override fun openFolder() = hasFolderApp
        override fun share(item: DownloadItem) = fileExists(item) && hasShareApp
        override suspend fun deleteFile(item: DownloadItem) { deletedFiles += item.id }
        override fun copyLink(url: String) { commandsLog += "copy $url" }
        override fun freeSpace() = 12_000L
    }

    private fun navigator(look: Look) = DownloadsNavigator(context, downloads, commands, device,
        CoroutineScope(Dispatchers.Unconfined), object : DownloadsNavigator.Host {
            override fun render(screen: Screen, focus: Focus?) {
                this@DownloadsNavigatorTest.screen = screen
                if (focus != null) this@DownloadsNavigatorTest.focus = focus
            }
            override fun showMessage(icon: Int, message: String) { messages += message }
            override fun confirmClearAll(request: DownloadsNavigator.ClearAllRequest, onConfirm: (Boolean) -> Unit) {
                clearAllRequest = request
                clearAllAnswer?.let(onConfirm)
            }
            override fun showAll(focusId: Long?) { shownAll = focusId }
        }, look)

    private fun item(id: Long, status: Int = Download.Status.DONE, name: String = "file$id.zip", time: Long = id * 1000,
                     url: String = "https://site.com/$name", mimeType: String? = null, resumable: Boolean = false,
                     size: Long = 100) =
        DownloadItem(id = id, time = time, filename = name, filepath = "/sdcard/Download/$name", url = url, size = size,
            bytesReceived = if (status == Download.Status.DONE) size else 10, status = status, mimeType = mimeType,
            resumable = resumable)

    private fun shownIds() = screen.items.map { it.id }

    /** The browser keeps the download service bound while a screen is shown */
    @Test
    fun theScreenTellsWhenItIsShownAndClosedOnce() {
        val navigator = navigator(Look.SCREEN)
        navigator.start()
        navigator.start(focusId = 1)
        navigator.stop()
        navigator.stop()
        assertEquals(listOf("shown", "closed"), screenLog)
    }

    @Test
    fun panelShowsActiveDownloadsFirstThenTheLatestFive() {
        downloads.value = listOf(item(8), item(7), item(6, Download.Status.IN_PROGRESS), item(5), item(4),
            item(3, Download.Status.PAUSED), item(2), item(1))
        navigator(Look.PANEL).start()
        assertEquals(listOf(6L, 3L, 8L, 7L, 5L), shownIds())
        assertTrue(screen.filters.isEmpty())
        //focus starts on the first row
        assertEquals(Focus(null).key, focus!!.key)
        assertEquals(0, focus!!.index)
    }

    @Test
    fun theListFollowsTheDownloadsWithoutMovingTheFocus() {
        downloads.value = listOf(item(1))
        val navigator = navigator(Look.SCREEN)
        navigator.start(focusId = 1)
        assertEquals(DownloadsNavigator.keyOf(1), focus!!.key)
        focus = null
        downloads.value = listOf(item(2, Download.Status.IN_PROGRESS), item(1))
        assertEquals(listOf(2L, 1L), shownIds())
        assertNull(focus)
    }

    @Test
    fun filtersAreTheKindsThereAreFilesOf() {
        downloads.value = listOf(
            item(1, name = "app.apk"),
            item(2, name = "movie.mkv"),
            item(3, name = "notes", mimeType = "application/pdf"),
            item(4, name = "data.bin"))
        val navigator = navigator(Look.SCREEN)
        navigator.start()
        assertEquals(listOf(Kind.APP, Kind.VIDEO, Kind.DOCUMENT, Kind.OTHER), screen.filters)
        assertNull(screen.filter)
        assertEquals(12_000L, screen.freeSpace)

        navigator.setFilter(Kind.VIDEO)
        assertEquals(listOf(2L), shownIds())
        assertEquals(DownloadsNavigator.filterKey(Kind.VIDEO), focus!!.key)

        //the last video is gone: all downloads are shown again
        downloads.value = downloads.value.filter { it.id != 2L }
        assertNull(screen.filter)
        assertEquals(listOf(1L, 3L, 4L), shownIds().sortedBy { it })
    }

    @Test
    fun kindsComeFromTheMimeTypeThenTheExtension() {
        assertEquals(Kind.APP, DownloadsNavigator.kindOf(item(1, name = "x",
            mimeType = "application/vnd.android.package-archive")))
        assertEquals(Kind.IMAGE, DownloadsNavigator.kindOf(item(1, name = "x.bin", mimeType = "image/png")))
        assertEquals(Kind.IMAGE, DownloadsNavigator.kindOf(item(1, name = "photo.JPG",
            mimeType = "application/octet-stream")))
        assertEquals(Kind.DOCUMENT, DownloadsNavigator.kindOf(item(1, name = "book.epub")))
        assertEquals(Kind.OTHER, DownloadsNavigator.kindOf(item(1, name = "archive.zip")))
    }

    @Test
    fun actionsFollowTheStateAndTheLook() {
        val done = item(1, name = "app.apk")
        val inProgress = item(2, Download.Status.IN_PROGRESS)
        val resumable = item(3, Download.Status.IN_PROGRESS, resumable = true)
        val paused = item(4, Download.Status.PAUSED, resumable = true)
        val failed = item(5, Download.Status.FAILED)
        val blob = item(6, Download.Status.FAILED, url = "blob:https://site.com/123")

        val panel = navigator(Look.PANEL)
        assertEquals(listOf(Action.INSTALL, Action.OPEN_FOLDER, Action.COPY_LINK, Action.SHARE, Action.SHOW_IN_ALL,
            Action.DELETE_FILE), panel.actionsOf(done))
        assertEquals(listOf(Action.CANCEL, Action.COPY_LINK), panel.actionsOf(inProgress))
        assertEquals(listOf(Action.PAUSE, Action.CANCEL, Action.COPY_LINK), panel.actionsOf(resumable))
        assertEquals(listOf(Action.RESUME, Action.CANCEL, Action.COPY_LINK), panel.actionsOf(paused))
        assertEquals(listOf(Action.DOWNLOAD_AGAIN, Action.COPY_LINK, Action.REMOVE_FROM_LIST), panel.actionsOf(failed))
        assertEquals(listOf(Action.REMOVE_FROM_LIST), panel.actionsOf(blob))
        assertEquals(listOf(Action.DOWNLOAD_AGAIN), panel.rowButtonsOf(failed))
        assertEquals(listOf(Action.CANCEL), panel.rowButtonsOf(inProgress))
        assertEquals(listOf(Action.RESUME, Action.CANCEL), panel.rowButtonsOf(paused))

        assertEquals(listOf(Action.OPEN, Action.OPEN_FOLDER, Action.COPY_LINK, Action.SHARE, Action.DOWNLOAD_AGAIN,
            Action.REMOVE_FROM_LIST, Action.DELETE_FILE), navigator(Look.SCREEN).actionsOf(item(7)))
        assertEquals(listOf(Action.OPEN, Action.OPEN_FOLDER, Action.COPY_LINK, Action.SHARE, Action.DOWNLOAD_AGAIN,
            Action.REMOVE_FROM_LIST, Action.DELETE_FILE), navigator(Look.PHONE).actionsOf(item(7)))
        //the screen has no download again button on failed rows: OK does it
        assertTrue(navigator(Look.SCREEN).rowButtonsOf(failed).isEmpty())
    }

    @Test
    fun okOpensInstallsOrDownloadsAgain() {
        val navigator = navigator(Look.SCREEN)
        assertEquals(Action.INSTALL, navigator.primaryAction(item(1, name = "app.apk")))
        assertEquals(Action.OPEN, navigator.primaryAction(item(2, name = "split.apks")))
        assertEquals(Action.DOWNLOAD_AGAIN, navigator.primaryAction(item(3, Download.Status.CANCELLED)))
        assertNull(navigator.primaryAction(item(4, Download.Status.FAILED, url = "data:text/plain,hi")))
        assertNull(navigator.primaryAction(item(5, Download.Status.IN_PROGRESS)))

        navigator.onRowClick(item(1, name = "app.apk"))
        assertEquals(listOf("install 1"), commandsLog)
    }

    @Test
    fun aFileThatIsGoneIsReported() {
        downloads.value = listOf(item(1).copy(filepath = ""))
        val navigator = navigator(Look.SCREEN)
        navigator.start()
        navigator.perform(screen.items[0], Action.OPEN)
        assertEquals(listOf(context.getString(com.phlox.tvwebbrowser.R.string.file_not_found)), messages)
    }

    @Test
    fun removingARowFocusesTheNextOne() {
        downloads.value = listOf(item(3), item(2), item(1))
        val navigator = navigator(Look.SCREEN)
        navigator.start()
        navigator.perform(screen.items[1], Action.REMOVE_FROM_LIST)
        assertEquals(listOf(3L, 1L), shownIds())
        assertEquals(DownloadsNavigator.keyOf(1), focus!!.key)
        assertTrue(deletedFiles.isEmpty())

        //the last row: the one before it
        navigator.perform(screen.items[1], Action.DELETE_FILE)
        assertEquals(listOf(3L), shownIds())
        assertEquals(DownloadsNavigator.keyOf(3), focus!!.key)
        assertEquals(listOf(1L), deletedFiles)
    }

    @Test
    fun downloadingAgainReplacesAFailedRowOnly() {
        downloads.value = listOf(item(2, Download.Status.FAILED), item(1))
        val navigator = navigator(Look.SCREEN)
        navigator.start()
        navigator.perform(screen.items[1], Action.DOWNLOAD_AGAIN)
        navigator.perform(screen.items[0], Action.DOWNLOAD_AGAIN)
        assertEquals(listOf("again 1", "again 2"), commandsLog)
        assertEquals(listOf(1L), shownIds())
    }

    @Test
    fun clearAllKeepsTheActiveDownloadsAndTheFilesUnlessAsked() {
        downloads.value = listOf(item(4, Download.Status.PAUSED), item(3, Download.Status.FAILED),
            item(2, size = 300), item(1, Download.Status.IN_PROGRESS))
        val navigator = navigator(Look.SCREEN)
        navigator.start()
        clearAllAnswer = false
        navigator.clearAll()
        assertEquals(2, clearAllRequest!!.count)
        assertEquals(2, clearAllRequest!!.activeCount)
        assertEquals(300L, clearAllRequest!!.filesSize)
        assertEquals(listOf(4L, 1L), shownIds().sortedDescending())
        assertTrue(deletedFiles.isEmpty())
        assertEquals("2 downloads removed from the list", messages.last())

        downloads.value = downloads.value + item(5)
        clearAllAnswer = true
        navigator.clearAll()
        assertEquals(listOf(5L), deletedFiles)

        //nothing left to clear
        navigator.clearAll()
        assertEquals(context.getString(com.phlox.tvwebbrowser.R.string.nothing_to_clear), messages.last())
    }

    @Test
    fun theOtherActions() {
        downloads.value = listOf(item(2, Download.Status.PAUSED, resumable = true), item(1))
        val navigator = navigator(Look.PANEL)
        navigator.start()
        navigator.perform(screen.items[1], Action.SHOW_IN_ALL)
        assertEquals(1L, shownAll)
        navigator.perform(screen.items[1], Action.COPY_LINK)
        navigator.perform(screen.items[0], Action.RESUME)
        navigator.perform(screen.items[0], Action.CANCEL)
        assertEquals(listOf("copy https://site.com/file1.zip", "resume 2", "cancel 2"), commandsLog)

        hasFolderApp = false
        navigator.openFolder()
        assertEquals(context.getString(com.phlox.tvwebbrowser.R.string.no_folder_app), messages.last())

        //a TV may have no app to share files with
        hasShareApp = false
        navigator.perform(screen.items[1], Action.SHARE)
        assertEquals(context.getString(com.phlox.tvwebbrowser.R.string.no_app_to_share), messages.last())
    }
}
