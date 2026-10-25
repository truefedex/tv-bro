package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.data.PairedDevicesRepository
import com.phlox.tvwebbrowser.model.PairedDevice
import com.phlox.tvwebbrowser.sync.BookmarkSelection
import com.phlox.tvwebbrowser.sync.BookmarksReplacer
import com.phlox.tvwebbrowser.sync.LocalBookmarks
import com.phlox.tvwebbrowser.sync.SyncBookmark
import com.phlox.tvwebbrowser.sync.SyncChannel
import com.phlox.tvwebbrowser.sync.SyncClient
import com.phlox.tvwebbrowser.sync.SyncException
import com.phlox.tvwebbrowser.sync.SyncLink
import com.phlox.tvwebbrowser.sync.SyncRequests
import com.phlox.tvwebbrowser.sync.SyncTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * What the page of the phone that sends to TVs shows and does, without views: the paired TVs (whether each
 * is found now), the actions for a TV, the choice of tabs and of bookmarks to send, files to send (chosen in
 * the picker of the system, with their progress; Back cancels them), the choice of bookmarks
 * of the TV to take (into the [local] bookmarks of the phone, which can then be brought back), pairing with
 * the link of a QR code. A stack of screens: [back] goes to the previous one. The phone shell draws it
 * through [Host].
 */
class PhoneSyncNavigator(
    private val context: Context,
    private val client: SyncClient,
    private val devices: PairedDevicesRepository,
    private val favorites: FavoritesRepository,
    /** The bookmarks of this phone, as the bookmarks taken from a TV change them */
    private val local: LocalBookmarks,
    private val scope: CoroutineScope,
    private val host: Host
) {
    companion object {
        private const val TAG = "PhoneSyncNavigator"
        /** How often the progress of a file is shown */
        private const val PROGRESS_INTERVAL = 200L
    }

    interface Host {
        /** @param busy what is being done (the screen waits for it), null if nothing */
        fun render(screen: Screen, busy: Busy?)
        fun showMessage(message: String)
        /** The phone may not reach devices of the local network until the user allows it */
        fun ensureLocalNetworkAccess(onGranted: () -> Unit)
        /** @param title the question: which device gets the changes */
        fun confirmBookmarks(title: String, changes: BookmarksReplacer.Changes, onConfirm: () -> Unit)
        /** The bookmarks of this phone changed */
        fun onBookmarksChanged()
        fun confirmForget(tv: PairedDevice, onConfirm: () -> Unit)
        /** The pages of the tabs of the phone, and the index of the current one (-1 if none) */
        fun tabs(): Pair<List<SyncTab>, Int>
        /** Lets the user choose files; [onPicked] does not come if the user chose none */
        fun pickFiles(onPicked: (List<Uri>) -> Unit)
        /** The screen stays on while files are sent, so the phone does not leave the network */
        fun keepScreenOn(on: Boolean)
        fun close()
    }

    /** @param cancellable the work can be cancelled ([cancel], or Back) */
    data class Busy(val text: String, val cancellable: Boolean = false)

    enum class TvStatus { CHECKING, FOUND, NOT_FOUND, FORGOT_PHONE }

    data class TvItem(val tv: PairedDevice, val status: TvStatus)

    data class TabItem(val tab: SyncTab, val checked: Boolean)

    sealed class Screen {
        data class Tvs(val tvs: List<TvItem>) : Screen()
        /** @param canUndo the bookmarks this phone took last can be given back */
        data class Tv(val tv: PairedDevice, val canUndo: Boolean = false) : Screen()
        data class Tabs(val tv: PairedDevice, val tabs: List<TabItem>) : Screen()
        /** @param fromTv the bookmarks of the TV, to take into this phone (else the ones of the phone, to send) */
        data class Bookmarks(val tv: PairedDevice, val rows: List<BookmarkSelection.Row>,
                             val all: BookmarkSelection.State, val fromTv: Boolean) : Screen()
    }

    private val stack = ArrayList<Screen>()
    private var busy: Busy? = null
    /** The files being sent */
    private var transfer: Transfer? = null
    private var tvsJob: Job? = null
    private val statuses = HashMap<String, TvStatus>()
    private var selection: BookmarkSelection? = null

    val screen: Screen? get() = stack.lastOrNull()

    /** Opens the list of the TVs */
    fun start() {
        stack.clear()
        busy = null
        stack += Screen.Tvs(emptyList())
        watchTvs()
        render()
    }

    fun stop() {
        cancel()
        tvsJob?.cancel()
        tvsJob = null
        stack.clear()
        selection = null
    }

    /** @return false if there is no screen to go back to */
    fun back(): Boolean {
        busy?.let {
            if (it.cancellable) cancel()
            return true
        }
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        if (stack.last() is Screen.Tvs) checkTvs()
        render()
        return true
    }

    private fun render() {
        var screen = stack.lastOrNull() ?: return
        if (screen is Screen.Tv) screen = screen.copy(canUndo = local.canRestore)
        host.render(screen, busy)
    }

    private fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = screen
        render()
    }

    /**
     * Runs the [block] with the screen waiting; failures of the sync are told to the user
     * @param onEnd after the block, also when it failed
     */
    private fun work(message: String, tv: PairedDevice?, cancellable: Boolean = false, onEnd: () -> Unit = {},
                     block: suspend () -> Unit) {
        busy = Busy(message, cancellable)
        render()
        scope.launch {
            try {
                block()
            } catch (e: IOException) {
                Log.w(TAG, "sync failed", e)
                host.showMessage(messageOf(e, tv))
            } catch (e: Exception) {
                //the keystore may fail on some devices
                Log.e(TAG, "sync failed", e)
                host.showMessage(context.getString(R.string.sync_failed))
            } finally {
                onEnd()
                busy = null
                render()
            }
        }
    }

    /** Tells what the screen waits for now; nothing if it waits for nothing */
    private fun updateBusy(message: String) {
        val busy = busy ?: return
        this.busy = busy.copy(text = message)
        render()
    }

    private fun messageOf(e: IOException, tv: PairedDevice?): String {
        val name = tv?.name ?: ""
        return when ((e as? SyncException)?.code) {
            SyncClient.UNREACHABLE -> context.getString(R.string.sync_unreachable, name)
            SyncChannel.UNKNOWN_PAIRING -> context.getString(R.string.sync_tv_forgot, name)
            SyncRequests.CHANGED -> context.getString(R.string.sync_tv_changed)
            SyncRequests.UNSUPPORTED -> context.getString(R.string.sync_tv_too_old, name)
            SyncRequests.NEED_PERMISSION -> context.getString(R.string.sync_file_need_permission, name)
            SyncRequests.STORAGE -> context.getString(R.string.sync_file_storage, name)
            SyncRequests.CANCELLED -> context.getString(R.string.sync_file_cancelled_on_tv, name)
            else -> context.getString(R.string.sync_failed)
        }
    }

    private fun watchTvs() {
        tvsJob?.cancel()
        var checked = false
        tvsJob = scope.launch {
            devices.observe(PairedDevice.KIND_TV).collect { tvs ->
                updateTvs(tvs)
                if (!checked) {
                    checked = true
                    checkTvs()
                }
            }
        }
    }

    private var tvs: List<PairedDevice> = emptyList()

    private fun updateTvs(tvs: List<PairedDevice>) {
        this.tvs = tvs
        val index = stack.indexOfFirst { it is Screen.Tvs }
        if (index == -1) return
        stack[index] = Screen.Tvs(tvs.map { TvItem(it, statuses[it.pairingId] ?: TvStatus.CHECKING) })
        if (index == stack.lastIndex) render()
    }

    /** Whether each TV is found now (a session without requests) */
    private fun checkTvs() {
        host.ensureLocalNetworkAccess {
            for (tv in tvs) {
                statuses[tv.pairingId] = TvStatus.CHECKING
                scope.launch {
                    statuses[tv.pairingId] = try {
                        client.session(tv) {}
                        TvStatus.FOUND
                    } catch (e: SyncException) {
                        if (e.code == SyncChannel.UNKNOWN_PAIRING) TvStatus.FORGOT_PHONE else TvStatus.NOT_FOUND
                    } catch (e: IOException) {
                        TvStatus.NOT_FOUND
                    }
                    updateTvs(tvs)
                }
            }
            updateTvs(tvs)
        }
    }

    fun openTv(tv: PairedDevice) {
        if (busy != null) return
        stack += Screen.Tv(tv)
        render()
    }

    /** Pairs with the TV of the QR code; its screen opens when it is done, after [onPaired] */
    fun pair(link: SyncLink, onPaired: () -> Unit) {
        if (stack.isEmpty()) start()
        host.ensureLocalNetworkAccess {
            work(context.getString(R.string.sync_pairing, link.tvName), null) {
                val tv = try {
                    client.pair(link)
                } catch (e: Exception) {
                    Log.w(TAG, "pairing failed", e)
                    host.showMessage(context.getString(R.string.sync_pairing_failed))
                    return@work
                }
                onPaired()
                statuses[tv.pairingId] = TvStatus.FOUND
                host.showMessage(context.getString(R.string.sync_paired, tv.name))
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack += Screen.Tv(tv)
            }
        }
    }

    fun forget(tv: PairedDevice) {
        host.confirmForget(tv) {
            work(context.getString(R.string.sync_connecting), tv) {
                //the TV forgets the phone too if it is found; the phone forgets it anyway
                try {
                    client.session(tv) { it.forget() }
                } catch (e: IOException) {
                    Log.i(TAG, "the TV is not told: $e")
                }
                devices.delete(tv)
                statuses.remove(tv.pairingId)
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
            }
        }
    }

    fun chooseTabs(tv: PairedDevice) {
        val (tabs, current) = host.tabs()
        stack += Screen.Tabs(tv, tabs.mapIndexed { i, tab -> TabItem(tab, checked = i == current) })
        render()
    }

    fun toggleTab(index: Int) {
        val screen = stack.lastOrNull() as? Screen.Tabs ?: return
        replaceTop(screen.copy(tabs = screen.tabs.mapIndexed { i, item -> if (i == index) item.copy(checked = !item.checked) else item }))
    }

    fun setAllTabs(checked: Boolean) {
        val screen = stack.lastOrNull() as? Screen.Tabs ?: return
        replaceTop(screen.copy(tabs = screen.tabs.map { it.copy(checked = checked) }))
    }

    fun sendTabs() {
        val screen = stack.lastOrNull() as? Screen.Tabs ?: return
        val tabs = screen.tabs.filter { it.checked }.map { it.tab }
        if (tabs.isEmpty()) {
            host.showMessage(context.getString(R.string.sync_nothing_selected))
            return
        }
        work(context.getString(R.string.sync_connecting), screen.tv) {
            val opened = client.session(screen.tv) { it.openTabs(tabs) }
            host.showMessage(context.resources.getQuantityString(R.plurals.sync_tabs_sent, opened, opened))
            stack.remove(screen)
        }
    }

    /** Lets the user choose files, then sends them to the [tv] */
    fun chooseFiles(tv: PairedDevice) {
        if (busy != null) return
        host.pickFiles { uris -> sendFiles(tv, uris) }
    }

    /** Stops sending files */
    fun cancel() {
        val transfer = transfer ?: return
        transfer.cancelled = true
        transfer.session?.cancel()
    }

    private class Transfer {
        @Volatile var session: SyncClient.Session? = null
        @Volatile var cancelled = false
    }

    private class PickedFile(val uri: Uri, val name: String, val size: Long, val mimeType: String?)

    /** A file of the phone could not be read */
    private class ReadFailure(cause: Exception) : IOException(cause)

    /** Sends the files one by one in one session, telling the progress */
    private fun sendFiles(tv: PairedDevice, uris: List<Uri>) {
        if (busy != null || uris.isEmpty()) return
        val transfer = Transfer()
        this.transfer = transfer
        host.keepScreenOn(true)
        val onEnd = {
            this.transfer = null
            host.keepScreenOn(false)
        }
        work(context.getString(R.string.sync_connecting), tv, cancellable = true, onEnd = onEnd) {
            val files = withContext(Dispatchers.IO) { uris.map { describe(it) } }
            var current: PickedFile? = null
            try {
                client.session(tv) { session ->
                    transfer.session = session
                    files.forEachIndexed { i, file ->
                        if (transfer.cancelled) throw IOException("cancelled")
                        current = file
                        sendFile(session, file, i, files.size)
                    }
                }
            } catch (e: ReadFailure) {
                Log.w(TAG, "can not read ${current?.uri}", e)
                host.showMessage(context.getString(R.string.sync_file_unreadable, current?.name ?: ""))
                return@work
            } catch (e: IOException) {
                val file = current
                when {
                    transfer.cancelled -> host.showMessage(context.getString(R.string.sync_files_cancelled))
                    //the TV closed the connection, or it was lost
                    e !is SyncException && file != null -> {
                        Log.w(TAG, "sending ${file.uri} failed", e)
                        host.showMessage(context.getString(R.string.sync_file_interrupted, file.name, tv.name))
                    }
                    else -> throw e
                }
                return@work
            }
            host.showMessage(context.resources.getQuantityString(R.plurals.sync_files_sent, files.size, files.size, tv.name))
        }
    }

    /** On the thread of the session */
    private fun sendFile(session: SyncClient.Session, file: PickedFile, index: Int, count: Int) {
        val input = try {
            context.contentResolver.openInputStream(file.uri) ?: throw IOException("no stream")
        } catch (e: Exception) {
            throw ReadFailure(e)
        }
        //failures of the file are told apart from failures of the connection
        val reading = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                input.read(b, off, len)
            } catch (e: Exception) {
                throw ReadFailure(e)
            }
        }
        input.use {
            var lastUpdate = 0L
            showProgress(file, index, count, 0L)
            session.sendFile(file.name, file.mimeType, file.size, reading) { sent ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= PROGRESS_INTERVAL) {
                    lastUpdate = now
                    showProgress(file, index, count, sent)
                }
            }
        }
    }

    private fun showProgress(file: PickedFile, index: Int, count: Int, sent: Long) {
        val progress = if (file.size > 0) "${(sent * 100 / file.size).coerceIn(0, 100)}%"
            else Formatter.formatShortFileSize(context, sent)
        val text = if (count == 1) context.getString(R.string.sync_file_sending, file.name, progress)
            else context.getString(R.string.sync_files_sending, file.name, index + 1, count, progress)
        scope.launch(Dispatchers.Main) { updateBusy(text) }
    }

    /** The name, size and type the provider of the file tells */
    private fun describe(uri: Uri): PickedFile {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn != -1) name = cursor.getString(nameColumn)
                    if (sizeColumn != -1 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "can not query $uri", e)
        }
        val type = try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        return PickedFile(uri, name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file", size, type)
    }

    /** @param fromTv choose bookmarks of the TV to take, else bookmarks of this phone to send */
    fun chooseBookmarks(tv: PairedDevice, fromTv: Boolean) {
        work(context.getString(R.string.sync_connecting), tv) {
            val selection = if (fromTv) {
                BookmarkSelection.of(client.session(tv) { it.getBookmarks() })
            } else {
                BookmarkSelection.load(favorites, context.getString(R.string.home_page))
            }
            this.selection = selection
            stack += Screen.Bookmarks(tv, selection.rows(), selection.state, fromTv)
        }
    }

    private fun bookmarksScreen(screen: Screen.Bookmarks, selection: BookmarkSelection) =
        screen.copy(rows = selection.rows(), all = selection.state)

    /** Chooses or unchooses the bookmark or folder (with all in it) */
    fun toggleBookmark(node: BookmarkSelection.Node) {
        val screen = stack.lastOrNull() as? Screen.Bookmarks ?: return
        val selection = selection ?: return
        selection.toggle(node)
        replaceTop(bookmarksScreen(screen, selection))
    }

    fun expandFolder(node: BookmarkSelection.Node) {
        val screen = stack.lastOrNull() as? Screen.Bookmarks ?: return
        val selection = selection ?: return
        node.expanded = !node.expanded
        replaceTop(bookmarksScreen(screen, selection))
    }

    fun setAllBookmarks(checked: Boolean) {
        val screen = stack.lastOrNull() as? Screen.Bookmarks ?: return
        val selection = selection ?: return
        selection.setAll(checked)
        replaceTop(bookmarksScreen(screen, selection))
    }

    /** Asks the TV what would change, lets the user confirm, then sends */
    fun sendBookmarks() {
        val screen = stack.lastOrNull() as? Screen.Bookmarks ?: return
        val tree = selection?.tree()
        if (tree == null) {
            host.showMessage(context.getString(R.string.sync_nothing_selected))
            return
        }
        if (screen.fromTv) {
            takeBookmarks(screen, tree)
            return
        }
        work(context.getString(R.string.sync_connecting), screen.tv) {
            val preview = client.session(screen.tv) { it.previewBookmarks(tree) }
            if (preview.isEmpty) {
                host.showMessage(context.getString(R.string.sync_bookmarks_same))
                return@work
            }
            host.confirmBookmarks(context.getString(R.string.sync_bookmarks_preview_title, screen.tv.name), preview) {
                work(context.getString(R.string.sync_connecting), screen.tv) {
                    client.session(screen.tv) { it.replaceBookmarks(tree, preview) }
                    host.showMessage(context.getString(R.string.sync_bookmarks_sent, screen.tv.name))
                    stack.remove(screen)
                    selection = null
                }
            }
        }
    }

    /** Puts the chosen bookmarks of the TV into the ones of this phone, after the user confirms what changes */
    private fun takeBookmarks(screen: Screen.Bookmarks, tree: SyncBookmark.Folder) {
        work(context.getString(R.string.sync_connecting), screen.tv) {
            val preview = local.preview(tree)
            if (preview.isEmpty) {
                host.showMessage(context.getString(R.string.sync_bookmarks_same_phone))
                return@work
            }
            host.confirmBookmarks(context.getString(R.string.sync_bookmarks_preview_title_phone), preview) {
                work(context.getString(R.string.sync_connecting), screen.tv) {
                    local.replace(tree, preview)
                    host.onBookmarksChanged()
                    host.showMessage(context.getString(R.string.sync_bookmarks_received))
                    stack.remove(screen)
                    selection = null
                }
            }
        }
    }

    /** Gives this phone back the bookmarks it had before it took the last ones from a TV */
    fun undoBookmarks() {
        work(context.getString(R.string.sync_connecting), null) {
            if (local.restore() != null) {
                host.onBookmarksChanged()
                host.showMessage(context.getString(R.string.sync_bookmarks_restored))
            }
        }
    }
}
