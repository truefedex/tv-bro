package com.phlox.tvwebbrowser.browser

import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A download as the screens show it: a snapshot of its database row, with its current speed */
data class DownloadItem(
    val id: Long,
    val time: Long,
    val filename: String,
    val filepath: String,
    val url: String,
    /** 0 when it is not known */
    val size: Long,
    val bytesReceived: Long,
    /** One of [Download.Status] */
    val status: Int,
    val mimeType: String? = null,
    /** The page the download was started from */
    val referer: String? = null,
    val error: String? = null,
    val resumable: Boolean = false,
    val bytesPerSecond: Long = 0
) {
    /** In progress or paused: not finished yet */
    val isActive: Boolean get() = status == Download.Status.IN_PROGRESS || status == Download.Status.PAUSED

    /** For the file actions, which take the entity */
    fun toDownload(): Download = Download().also {
        it.id = id
        it.time = time
        it.filename = filename
        it.filepath = filepath
        it.url = url
        it.size = size
        it.bytesReceived = bytesReceived
        it.status = status
        it.mimeType = mimeType
        it.referer = referer
        it.error = error
        it.resumable = resumable
    }

    companion object {
        fun of(download: Download, bytesPerSecond: Long = 0) = DownloadItem(
            download.id, download.time, download.filename, download.filepath, download.url, download.size,
            download.bytesReceived, download.status, download.mimeType, download.referer, download.error,
            download.resumable, bytesPerSecond)
    }
}

/** What happened to a download, for the toasts and snackbars */
sealed class DownloadEvent(val item: DownloadItem) {
    class Completed(item: DownloadItem) : DownloadEvent(item)
    class Failed(item: DownloadItem) : DownloadEvent(item)
}

/**
 * Turns the rows of the downloads, as the database emits them, into [DownloadItem]s with their
 * speeds (from the received bytes of the rows one after another) and into the events of
 * downloads that ended since the previous rows.
 */
class DownloadsTracker {
    companion object {
        /** Shorter intervals between samples give jumpy speeds */
        const val MIN_SAMPLE_INTERVAL = 500L
        /** The weight of the newest sample in the speed */
        private const val SMOOTHING = 0.3
    }

    private class Sample(val bytes: Long, val time: Long, val bytesPerSecond: Long)

    private val samples = HashMap<Long, Sample>()
    private var statuses: Map<Long, Int>? = null

    fun track(rows: List<Download>, now: Long): Pair<List<DownloadItem>, List<DownloadEvent>> {
        val events = ArrayList<DownloadEvent>()
        val previousStatuses = statuses
        val items = rows.map { row ->
            val item = DownloadItem.of(row, speedOf(row, now))
            if (previousStatuses?.get(row.id) == Download.Status.IN_PROGRESS) {
                when (row.status) {
                    Download.Status.DONE -> events.add(DownloadEvent.Completed(item))
                    Download.Status.FAILED -> events.add(DownloadEvent.Failed(item))
                }
            }
            item
        }
        statuses = rows.associate { it.id to it.status }
        samples.keys.retainAll(rows.filter { it.status == Download.Status.IN_PROGRESS }.map { it.id }.toSet())
        return items to events
    }

    private fun speedOf(row: Download, now: Long): Long {
        if (row.status != Download.Status.IN_PROGRESS) return 0
        val sample = samples[row.id]
        if (sample == null || row.bytesReceived < sample.bytes) {
            samples[row.id] = Sample(row.bytesReceived, now, 0)
            return 0
        }
        val interval = now - sample.time
        if (interval < MIN_SAMPLE_INTERVAL) return sample.bytesPerSecond
        val current = (row.bytesReceived - sample.bytes) * 1000 / interval
        val speed = if (sample.bytesPerSecond == 0L) current
            else (sample.bytesPerSecond * (1 - SMOOTHING) + current * SMOOTHING).toLong()
        samples[row.id] = Sample(row.bytesReceived, now, speed)
        return speed
    }
}

/**
 * The downloads of the browser processes: the download service (in its own process) writes them
 * to the database, this model observes it.
 */
class DownloadsModel : ActiveModel() {
    private val tracker = DownloadsTracker()
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    /** All the downloads, the latest first */
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()
    private val _activeCount = MutableStateFlow(0)
    /** How many downloads are in progress or paused */
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()
    private val _inProgress = MutableStateFlow(false)
    /** Whether a download is in progress (not paused); a row a dead process left in progress too */
    val inProgress: StateFlow<Boolean> = _inProgress.asStateFlow()
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    init {
        modelScope.launch {
            TVBro.container.data.downloads.observeAll().collect { rows ->
                val (items, events) = tracker.track(rows, System.currentTimeMillis())
                _downloads.value = items
                _activeCount.value = items.count { it.isActive }
                _inProgress.value = items.any { it.status == Download.Status.IN_PROGRESS }
                events.forEach { _events.tryEmit(it) }
            }
        }
    }
}
