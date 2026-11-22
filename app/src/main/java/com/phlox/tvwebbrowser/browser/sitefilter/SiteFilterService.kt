package com.phlox.tvwebbrowser.browser.sitefilter

import android.content.Context
import android.os.Process
import android.util.Log
import com.phlox.tvwebbrowser.adblock.AdblockFormatException
import com.phlox.tvwebbrowser.adblock.sitefilter.SiteFilter
import com.phlox.tvwebbrowser.adblock.sitefilter.SiteFilterCompiler
import com.phlox.tvwebbrowser.browser.adblock.HttpListFetcher
import com.phlox.tvwebbrowser.browser.adblock.ListFetcher
import com.phlox.tvwebbrowser.browser.adblock.ListMeta
import com.phlox.tvwebbrowser.browser.adblock.ListUpdates
import com.phlox.tvwebbrowser.profile.ProfileLock
import com.phlox.tvwebbrowser.webengine.SiteCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The lists of sites of the site filter ([SiteCategory]) and the [filter] built of them. Only the lists the profiles
 * want ([Wanted]: the kids profile while it is on) are downloaded, once a month; each is parsed into its category of
 * `sites.bin` (the filter: the hashes of the domains of each category, see [SiteFilter]) and is not kept: a list
 * downloaded again replaces its own category in the filter as it is ([SiteFilterCompiler] on it). The filter is in
 * memory only while the kids profile is shown ([setShown]); a check of the lists that are due reads nothing of it.
 * The [mask] of the categories wanted picks the lists a check looks at (a category turned off stays in the file).
 *
 * Files in [dir]: `lists.json` (the [ListMeta] of each list, the categories the filter has and the address of the own
 * list) and `sites.bin`. A list that was never downloaded is tried again soon (nothing is filtered without it); a
 * failed update keeps the category as it was.
 */
class SiteFilterService(
    private val dir: File,
    private val scope: CoroutineScope,
    private val fetcher: ListFetcher = HttpListFetcher(),
    private val now: () -> Long = System::currentTimeMillis,
    private val wanted: () -> Wanted,
) {
    /** The categories the profiles block and the address of the own list ("" for none) */
    data class Wanted(val categories: Set<SiteCategory>, val customUrl: String) {
        val mask: Long get() = categories.filter { it != SiteCategory.CUSTOM }.fold(0L) { m, c -> m or c.bit } or
            (if (customUrl.isNotEmpty()) SiteCategory.CUSTOM.bit else 0L)

        companion object {
            val NONE = Wanted(emptySet(), "")
        }
    }

    data class Status(
        val loaded: Boolean = false,
        val updating: Boolean = false,
        /** The domains of the lists wanted */
        val domains: Int = 0,
        /** Lists wanted that were never downloaded: their sites are not blocked yet */
        val missing: Int = 0,
        /** The last download of a list wanted failed */
        val failed: Boolean = false,
        /** The latest successful update of a list wanted; 0: none */
        val lastUpdate: Long = 0,
    )

    /** The filter while the kids profile is shown ([setShown]); null otherwise or while it has no list */
    @Volatile
    var filter: SiteFilter? = null
        private set

    /** The categories wanted now as a mask of [SiteCategory.bit]s; 0 when nothing is to be blocked */
    @Volatile
    var mask: Long = 0
        private set

    private val statusFlow = MutableStateFlow(Status())
    val status: StateFlow<Status> = statusFlow.asStateFlow()

    private val metasFile = File(dir, LISTS_FILE)
    private val filterFile = File(dir, FILTER_FILE)
    private val metas = HashMap<String, ListMeta>()
    private var customUrl = ""
    /** The categories the filter file has (kept in `lists.json` too, so a check of the updates does not read the file) */
    private var present = 0L
    /** The own list of the filter is of an address that is not the one wanted any more */
    private var customOutdated = false
    private var current = Wanted.NONE
    private var shown = false
    //the lists change daily, a month old list still blocks nearly all; a list never downloaded is tried again soon
    private val updates = ListUpdates(defaultIntervalHours = UPDATE_INTERVAL_HOURS, minIntervalHours = UPDATE_INTERVAL_HOURS,
        maxIntervalHours = UPDATE_INTERVAL_HOURS,
        missingRetryDelaysMs = longArrayOf(5 * MINUTE_MS, 30 * MINUTE_MS, HOUR_MS, 6 * HOUR_MS))
    private val lock = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    /** The load (or release) of the filter for the profile shown */
    @Volatile
    private var showing: Job? = null
    @Volatile
    private var started = false
    private var updating = false

    /** Reads what the lists are, then, if [scheduleUpdates], downloads them now and then */
    fun start(scheduleUpdates: Boolean) {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            initialize()
            if (!scheduleUpdates) return@launch
            val missing = lock.withLock { sources().any { !has(it.third) } }
            if (!missing) delay(START_DELAY_MS)
            while (true) {
                update(force = false)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun initialize() {
        try {
            lock.withLock {
                loadMetas()
                current = wanted()
                mask = current.mask
                applyCustomUrl()
                publish()
            }
        } finally {
            initialized.complete(Unit)
        }
    }

    /**
     * The kids profile takes the screen ([shown]): its filter is loaded; another profile: it is dropped from memory.
     * Pages of the kids profile wait for the load ([awaitLoaded]).
     */
    fun setShown(shown: Boolean): Job = scope.launch(Dispatchers.IO) {
        initialized.await()
        lock.withLock {
            this@SiteFilterService.shown = shown
            filter = if (shown) filter ?: readFilter() else null
        }
    }.also { showing = it }

    /** Waits for the filter of the kids profile to be loaded, at most [timeoutMs]; at once when not started */
    suspend fun awaitLoaded(timeoutMs: Long) {
        if (!started) return
        withTimeoutOrNull(timeoutMs) {
            initialized.await()
            showing?.join()
        }
    }

    /** The settings of the profiles changed: the lists they want now are downloaded */
    fun onWantedChanged(): Job = scope.launch(Dispatchers.IO) {
        initialized.await()
        lock.withLock {
            val wanted = wanted()
            if (wanted == current) return@withLock
            current = wanted
            mask = wanted.mask
            applyCustomUrl()
            updateLocked(force = false)
        }
    }

    /** Downloads the lists that are due (all wanted ones if [force]); false if a download failed */
    suspend fun update(force: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { updateLocked(force) }
    }

    // --- Under the lock ---

    /** The lists wanted: (id, url, category) */
    private fun sources(): List<Triple<String, String, SiteCategory>> {
        val list = current.categories.mapNotNull { c -> c.url?.let { Triple(c.id, it, c) } }.sortedBy { it.third.ordinal }
        return if (current.customUrl.isEmpty()) list
            else list + Triple(SiteCategory.CUSTOM.id, current.customUrl, SiteCategory.CUSTOM)
    }

    private fun has(category: SiteCategory) = present and category.bit != 0L

    /** Another address of the own list: the list of the old one goes from the filter with the next update */
    private fun applyCustomUrl() {
        if (current.customUrl == customUrl) return
        customUrl = current.customUrl
        metas.remove(SiteCategory.CUSTOM.id)
        if (has(SiteCategory.CUSTOM)) customOutdated = true
        saveMetas()
    }

    /** The filter of the file; null if there is none (or it can not be used, then it goes) */
    private fun readFilter(): SiteFilter? {
        if (!filterFile.exists()) {
            setPresent(0)
            return null
        }
        return try {
            val loaded = SiteFilter.load(filterFile)
            if (loaded.categoryCount != SiteCategory.entries.size) throw AdblockFormatException("categories")
            setPresent(loaded.present)
            loaded
        } catch (e: AdblockFormatException) {
            Log.w(TAG, "The filter file can not be used: ${e.message}")
            filterFile.delete()
            setPresent(0)
            null
        } catch (e: IOException) {
            Log.e(TAG, "Can not read the filter file", e)
            null
        }
    }

    /** The categories of the file as it is: the lists it lost are downloaded again */
    private fun setPresent(value: Long) {
        if (value == present) return
        present = value
        saveMetas()
        publish()
    }

    private fun updateLocked(force: Boolean): Boolean {
        setUpdating(true)
        var ok = true
        //the filter as it is, read only when a list came
        var compiler: SiteFilterCompiler? = null
        fun compiler() = compiler ?: SiteFilterCompiler(SiteCategory.entries.size, filter ?: readFilter())
            .also { compiler = it }
        val tid = Process.myTid()
        val priority = Process.getThreadPriority(tid)
        try {
            //the pages being loaded come first on weak TVs
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            if (customOutdated) {
                compiler().clear(SiteCategory.CUSTOM.ordinal)
                customOutdated = false
            }
            for ((id, url, category) in sources()) {
                val meta = meta(id)
                val exists = has(category)
                if (!force && !updates.isDue(meta, exists, now())) continue
                val temp = File(dir, "$id.txt.tmp")
                try {
                    dir.mkdirs()
                    val result = fetcher.fetch(url, meta.etag.takeIf { exists }, meta.lastModified.takeIf { exists }, temp)
                    if (result is ListFetcher.Result.Downloaded) {
                        ListUpdates.checkList(temp)
                        meta.filters = temp.bufferedReader().useLines { compiler().add(category.ordinal, it) }
                        meta.etag = result.etag
                        meta.lastModified = result.lastModified
                    }
                    meta.lastSuccess = now()
                    meta.failures = 0
                } catch (e: IOException) {
                    Log.w(TAG, "Can not update $id: $e")
                    meta.failures++
                    meta.lastAttempt = now()
                    ok = false
                } finally {
                    temp.delete()
                }
                saveMetas()
            }
            compiler?.let { build(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Can not build the filter", e)
            ok = false
        } finally {
            Process.setThreadPriority(tid, priority)
            setUpdating(false)
        }
        return ok
    }

    private fun build(compiler: SiteFilterCompiler) {
        val started = System.nanoTime()
        val built = compiler.build(fingerprint = SiteFilter.VERSION.toLong())
        present = try {
            built.writeTo(filterFile)
            built.present
        } catch (e: IOException) {
            //the filter still works until the process ends; without the file all lists are missing and come again soon
            Log.e(TAG, "Can not write the filter file", e)
            filterFile.delete()
            0
        }
        saveMetas()
        filter = if (shown) built else null
        Log.i(TAG, "Filter built: ${built.totalSize} domains in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    private fun meta(id: String) = metas.getOrPut(id) { ListMeta() }

    private fun loadMetas() {
        metas.clear()
        customUrl = ""
        present = 0
        try {
            if (!metasFile.exists()) return
            val json = JSONObject(metasFile.readText())
            customUrl = json.optString("customUrl")
            //what the file has, as written with it (the file itself is read when the kids profile is shown)
            present = if (filterFile.exists()) json.optLong("present") else 0
            val lists = json.optJSONObject("lists") ?: return
            for (id in lists.keys()) metas[id] = ListUpdates.metaOf(lists.getJSONObject(id))
        } catch (e: Exception) {
            Log.w(TAG, "Can not read $metasFile", e)
        }
    }

    private fun saveMetas() {
        val lists = JSONObject()
        for ((id, m) in metas) lists.put(id, ListUpdates.toJson(m))
        val json = JSONObject().put("customUrl", customUrl).put("present", present).put("lists", lists)
        try {
            dir.mkdirs()
            val temp = File(dir, "$LISTS_FILE.tmp")
            FileOutputStream(temp).use {
                it.write(json.toString().toByteArray())
                it.fd.sync()
            }
            ListUpdates.replace(temp, metasFile)
        } catch (e: IOException) {
            Log.e(TAG, "Can not write $metasFile", e)
        }
    }

    private fun setUpdating(value: Boolean) {
        updating = value
        publish()
    }

    private fun publish() {
        val wanted = sources()
        statusFlow.value = Status(
            loaded = initialized.isCompleted, updating = updating,
            domains = wanted.filter { has(it.third) }.sumOf { meta(it.first).filters },
            missing = wanted.count { !has(it.third) },
            failed = wanted.any { meta(it.first).failures > 0 },
            lastUpdate = wanted.maxOfOrNull { meta(it.first).lastSuccess } ?: 0,
        )
    }

    companion object {
        private const val TAG = "SiteFilterService"
        const val LISTS_FILE = "lists.json"
        const val FILTER_FILE = "sites.bin"
        private const val MINUTE_MS = 60_000L
        /** The lists are downloaded again once a month */
        const val UPDATE_INTERVAL_HOURS = 30 * 24
        private const val HOUR_MS = ListUpdates.HOUR_MS
        private const val START_DELAY_MS = 30_000L
        /** Often enough to retry a missing list soon; a check of the lists that are not due costs nothing */
        private const val CHECK_INTERVAL_MS = 15 * MINUTE_MS

        /** The lists the kids profile wants while it is on */
        fun wantedOf(context: Context): Wanted {
            val lock = ProfileLock.get(context)
            if (!lock.enabled) return Wanted.NONE
            return Wanted(lock.kidsSiteCategories, lock.kidsSiteListUrl)
        }
    }
}
