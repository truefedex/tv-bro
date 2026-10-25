package com.phlox.tvwebbrowser.activity.main

import android.content.Context
import androidx.annotation.DrawableRes
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadFileActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What the downloads screens show and do, without their look: the panel under the button of the TV
 * bar (the latest downloads), the full screen of the TV and the page of the phone (all downloads,
 * with filters by the kind of the file). The downloads are live: each change of them, of the filter
 * or of a download goes to [Host.render], with the row (or button) to focus when it has to move.
 */
class DownloadsNavigator(
    private val context: Context,
    private val downloads: Flow<List<DownloadItem>>,
    private val commands: Commands,
    private val device: Device,
    private val scope: CoroutineScope,
    private val host: Host,
    val look: Look
) {
    companion object {
        /** Rows of the panel */
        const val PANEL_SIZE = 5
        /** The button of the panel that opens all downloads */
        const val ALL_DOWNLOADS = "all"
        const val OPEN_FOLDER = "folder"
        const val CLEAR_ALL = "clear"

        fun keyOf(id: Long) = "d$id"
        fun filterKey(kind: Kind?) = "filter:${kind ?: "all"}"

        /** Only links can be downloaded again: blob and data urls are gone with their page */
        fun isRepeatable(item: DownloadItem): Boolean =
            item.url.startsWith("http://", ignoreCase = true) || item.url.startsWith("https://", ignoreCase = true)

        fun kindOf(item: DownloadItem): Kind {
            if (item.mimeType == DownloadFileActions.APK_MIME_TYPE) return Kind.APP
            val mimeType = item.mimeType?.lowercase()
            when {
                mimeType == null -> {}
                mimeType.startsWith("video/") -> return Kind.VIDEO
                mimeType.startsWith("image/") -> return Kind.IMAGE
                mimeType.startsWith("text/") || mimeType == "application/pdf" ||
                    mimeType.startsWith("application/vnd.openxmlformats") || mimeType == "application/msword" ||
                    mimeType.startsWith("application/vnd.ms-") || mimeType.startsWith("application/vnd.oasis") ||
                    mimeType == "application/epub+zip" || mimeType == "application/rtf" -> return Kind.DOCUMENT
            }
            return when (item.filename.substringAfterLast('.', "").lowercase()) {
                "apk", "apks", "xapk" -> Kind.APP
                "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "3gp", "flv", "wmv", "mpg", "mpeg" -> Kind.VIDEO
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico" -> Kind.IMAGE
                "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub",
                "csv", "md", "html", "htm", "fb2", "djvu" -> Kind.DOCUMENT
                else -> Kind.OTHER
            }
        }
    }

    interface Host {
        /** [focus] is null when the focus stays where it is (the downloads changed) */
        fun render(screen: Screen, focus: Focus?)
        fun showMessage(@DrawableRes icon: Int, message: String)
        /** Asks whether to clear the list; [onConfirm] gets whether to delete the files too */
        fun confirmClearAll(request: ClearAllRequest, onConfirm: (deleteFiles: Boolean) -> Unit)
        /** Opens all downloads (from the panel), with the download [focusId] focused, if any */
        fun showAll(focusId: Long?)
    }

    /** What the downloads screens need from their shell */
    interface ShellCallback {
        val downloads: Flow<List<DownloadItem>>
        val downloadCommands: Commands
        val downloadDevice: Device
        fun showMessage(@DrawableRes icon: Int, message: String)
        fun confirmClearAll(request: ClearAllRequest, onConfirm: (deleteFiles: Boolean) -> Unit)
    }

    /** What changes the downloads */
    interface Commands {
        fun pause(id: Long)
        /** Continues a paused download (the browser gives the cookies of the site again) */
        fun resume(item: DownloadItem)
        /** Stops a download and deletes its file */
        fun cancel(id: Long)
        /** Starts a new download of the url of the [item] */
        fun downloadAgain(item: DownloadItem)
        /** Removes the downloads from the list, their files stay */
        suspend fun remove(ids: List<Long>)
        /** The downloads were shown ([start]) or closed ([stop]): the commands of the screen are about to come */
        fun onScreenShown(shown: Boolean) {}
    }

    /** What the device does with the files of downloads */
    interface Device {
        fun fileExists(item: DownloadItem): Boolean
        /** @return false if the file is gone or no app opens it */
        fun open(item: DownloadItem): Boolean
        /** @return false if the file is gone or there is no installer */
        fun install(item: DownloadItem): Boolean
        /** Opens the folder of downloads in a file manager, @return false if there is none */
        fun openFolder(): Boolean
        /** @return false if the file is gone */
        fun share(item: DownloadItem): Boolean
        suspend fun deleteFile(item: DownloadItem)
        fun copyLink(url: String)
        /** The free space where the downloads go, in bytes; null when it is not known */
        fun freeSpace(): Long?
    }

    enum class Look { PANEL, SCREEN, PHONE }

    /** The kinds of files, the filters of all downloads */
    enum class Kind { APP, VIDEO, IMAGE, DOCUMENT, OTHER }

    /** What can be done to a download */
    enum class Action {
        OPEN, INSTALL, OPEN_FOLDER, COPY_LINK, SHARE, SHOW_IN_ALL, DOWNLOAD_AGAIN, PAUSE, RESUME, CANCEL,
        REMOVE_FROM_LIST, DELETE_FILE
    }

    /**
     * @param items the downloads to show: in progress and paused ones first, then the latest first
     * @param filters the kinds there are files of (the filters to show), [filter] the chosen one (null for all)
     * @param freeSpace in bytes, for all downloads (0 in the panel), null when it is not known
     */
    class Screen(val items: List<DownloadItem>, val filters: List<Kind>, val filter: Kind?, val freeSpace: Long?)

    /** Focus the row (or button) of the [key], else the row at [index] */
    class Focus(val key: String?, val index: Int = 0)

    /**
     * Clear all: [count] downloads go from the list, the [activeCount] ones in progress or paused stay;
     * their files take [filesSize] bytes
     */
    class ClearAllRequest(val count: Int, val activeCount: Int, val filesSize: Long)

    private var job: Job? = null
    private var filter: Kind? = null
    /** All the downloads, as they came last */
    private var all: List<DownloadItem> = emptyList()
    /** The downloads shown last */
    private var shown: List<DownloadItem> = emptyList()
    /** The focus for the next render */
    private var pendingFocus: Focus? = null

    /** Shows the downloads, with the download [focusId] focused (else the first one) */
    fun start(focusId: Long? = null) {
        if (job == null) commands.onScreenShown(true)
        job?.cancel()
        filter = null
        pendingFocus = Focus(focusId?.let { keyOf(it) })
        job = scope.launch {
            downloads.collect {
                all = it
                render()
            }
        }
    }

    /** The screen closed */
    fun stop() {
        val job = job ?: return
        job.cancel()
        this.job = null
        commands.onScreenShown(false)
    }

    fun setFilter(kind: Kind?) {
        if (filter == kind) return
        filter = kind
        pendingFocus = Focus(filterKey(kind))
        render()
    }

    private fun render() {
        val kinds = all.mapTo(HashSet()) { kindOf(it) }
        if (filter != null && filter !in kinds) filter = null
        val ordered = all.filter { it.isActive } + all.filter { !it.isActive }
        shown = when (look) {
            Look.PANEL -> ordered.take(PANEL_SIZE)
            else -> ordered.filter { filter == null || kindOf(it) == filter }
        }
        val filters = if (look == Look.PANEL) emptyList() else Kind.entries.filter { it in kinds }
        val freeSpace = if (look == Look.PANEL) 0L else device.freeSpace()
        val focus = pendingFocus
        pendingFocus = null
        host.render(Screen(shown, filters, filter, freeSpace), focus)
    }

    /** What OK on the row does: null if nothing */
    fun primaryAction(item: DownloadItem): Action? = when (item.status) {
        Download.Status.DONE -> if (isApk(item)) Action.INSTALL else Action.OPEN
        Download.Status.FAILED, Download.Status.CANCELLED -> if (isRepeatable(item)) Action.DOWNLOAD_AGAIN else null
        else -> null
    }

    fun onRowClick(item: DownloadItem) {
        primaryAction(item)?.let { perform(item, it) }
    }

    /** The options of a download, in the order of its menu */
    fun actionsOf(item: DownloadItem): List<Action> {
        val repeatable = isRepeatable(item)
        val actions = ArrayList<Action>()
        when (item.status) {
            Download.Status.DONE -> {
                actions += if (isApk(item)) Action.INSTALL else Action.OPEN
                actions += Action.OPEN_FOLDER
                if (repeatable) actions += Action.COPY_LINK
                actions += Action.SHARE
                if (look == Look.PANEL) {
                    actions += Action.SHOW_IN_ALL
                } else {
                    if (repeatable) actions += Action.DOWNLOAD_AGAIN
                    actions += Action.REMOVE_FROM_LIST
                }
                actions += Action.DELETE_FILE
            }
            Download.Status.IN_PROGRESS, Download.Status.PAUSED -> {
                if (item.resumable) actions += if (item.status == Download.Status.PAUSED) Action.RESUME else Action.PAUSE
                actions += Action.CANCEL
                if (repeatable) actions += Action.COPY_LINK
            }
            else -> {
                if (repeatable) {
                    actions += Action.DOWNLOAD_AGAIN
                    actions += Action.COPY_LINK
                }
                actions += Action.REMOVE_FROM_LIST
            }
        }
        return actions
    }

    /** The buttons on the row itself, before its menu button: pause or resume and cancel, or download again */
    fun rowButtonsOf(item: DownloadItem): List<Action> = when (item.status) {
        Download.Status.IN_PROGRESS -> if (item.resumable) listOf(Action.PAUSE, Action.CANCEL) else listOf(Action.CANCEL)
        Download.Status.PAUSED -> listOf(Action.RESUME, Action.CANCEL)
        Download.Status.FAILED, Download.Status.CANCELLED ->
            if (look == Look.PANEL && isRepeatable(item)) listOf(Action.DOWNLOAD_AGAIN) else emptyList()
        else -> emptyList()
    }

    fun perform(item: DownloadItem, action: Action) {
        when (action) {
            Action.OPEN -> if (!device.open(item)) host.showMessage(R.drawable.ic_error, context.getString(
                if (!device.fileExists(item)) R.string.file_not_found else R.string.no_app_for_file_type))
            Action.INSTALL -> if (!device.install(item)) host.showMessage(R.drawable.ic_error, context.getString(
                if (!device.fileExists(item)) R.string.file_not_found else R.string.error))
            Action.OPEN_FOLDER -> openFolder()
            Action.COPY_LINK -> {
                device.copyLink(item.url)
                host.showMessage(R.drawable.ic_link, context.getString(R.string.link_copied))
            }
            Action.SHARE -> if (!device.share(item)) host.showMessage(R.drawable.ic_error, context.getString(
                if (!device.fileExists(item)) R.string.file_not_found else R.string.no_app_to_share))
            Action.SHOW_IN_ALL -> host.showAll(item.id)
            Action.DOWNLOAD_AGAIN -> {
                commands.downloadAgain(item)
                //a failed download is replaced by the new one, a complete one stays with its file
                if (item.status != Download.Status.DONE) removeRow(item) { commands.remove(listOf(item.id)) }
            }
            Action.PAUSE -> commands.pause(item.id)
            Action.RESUME -> commands.resume(item)
            Action.CANCEL -> commands.cancel(item.id)
            Action.REMOVE_FROM_LIST -> removeRow(item) { commands.remove(listOf(item.id)) }
            Action.DELETE_FILE -> removeRow(item) {
                device.deleteFile(item)
                commands.remove(listOf(item.id))
            }
        }
    }

    fun openFolder() {
        if (!device.openFolder()) {
            host.showMessage(R.drawable.ic_error, context.getString(R.string.no_folder_app))
        }
    }

    /** Removes the row of the [item], moving the focus to the next row (the previous one for the last) */
    private fun removeRow(item: DownloadItem, remove: suspend () -> Unit) {
        val index = shown.indexOfFirst { it.id == item.id }
        if (index != -1) {
            val next = shown.getOrNull(index + 1) ?: shown.getOrNull(index - 1)
            pendingFocus = Focus(next?.let { keyOf(it.id) }, index)
        }
        scope.launch { remove() }
    }

    /** Asks to clear the list of all the downloads that ended, and clears it */
    fun clearAll() {
        val finished = all.filter { !it.isActive }
        if (finished.isEmpty()) {
            host.showMessage(R.drawable.ic_downloads, context.getString(R.string.nothing_to_clear))
            return
        }
        val filesSize = finished.filter { it.status == Download.Status.DONE }.sumOf { it.size }
        host.confirmClearAll(ClearAllRequest(finished.size, all.size - finished.size, filesSize)) { deleteFiles ->
            pendingFocus = Focus(null)
            scope.launch {
                if (deleteFiles) {
                    finished.filter { it.status == Download.Status.DONE }.forEach { device.deleteFile(it) }
                }
                commands.remove(finished.map { it.id })
                host.showMessage(R.drawable.ic_downloads, context.resources.getQuantityString(
                    R.plurals.downloads_removed, finished.size, finished.size))
            }
        }
    }

    /** Split packages (apks, xapk) are apps too, but the installer does not take them */
    private fun isApk(item: DownloadItem) = item.mimeType == DownloadFileActions.APK_MIME_TYPE ||
        item.filename.endsWith(".apk", ignoreCase = true)
}
