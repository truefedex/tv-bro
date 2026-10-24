package com.phlox.tvwebbrowser.browser.adblock

import android.os.Process
import android.util.Log
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.adblock.AdblockFormatException
import com.phlox.tvwebbrowser.adblock.FilterCompiler
import com.phlox.tvwebbrowser.adblock.FilterEngine
import com.phlox.tvwebbrowser.adblock.psl.PublicSuffixList
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
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32

/**
 * The ad blocker of a browser process (normal or incognito; they never run together): the [engine] compiled
 * from the enabled filter lists, its file, and the updates of the lists.
 *
 * Files in [dir]: `lists.json` ([FilterListsStore]), `lists/<id>.txt` (the downloaded lists, kept to build the
 * engine again without the network) and `engine.bin` (the compiled engine, written atomically). The engine is
 * replaced by a write of a volatile reference: checks running on the threads of the web engines finish with
 * the old one.
 *
 * A failed download keeps the list and its date and is tried again later (1 h, 6 h, then a day after); the
 * engine of the lists already there stays.
 */
class AdBlockService(
    private val dir: File,
    private val scope: CoroutineScope,
    private val config: Config,
    loadPsl: () -> PublicSuffixList,
    private val fetcher: ListFetcher = HttpListFetcher(),
    private val now: () -> Long = System::currentTimeMillis,
    language: String = Locale.getDefault().language,
) {
    data class ListState(
        val id: String, val title: String, val enabled: Boolean,
        /** The request filters the engine has of it */
        val filters: Int, val skipped: Int,
        /** 0: never downloaded */
        val lastUpdate: Long, val failed: Boolean,
    )

    data class Status(
        val loaded: Boolean = false,
        val updating: Boolean = false,
        /** The lists of the catalog, then the user's own list if it has an address */
        val lists: List<ListState> = emptyList(),
        val customUrl: String = "",
        /** The filters of the engine; 0 without one */
        val filters: Int = 0,
        /** The filters of the enabled lists that are not supported */
        val skipped: Int = 0,
        /** The latest successful update of an enabled list; 0: none */
        val lastUpdate: Long = 0,
    )

    @Volatile
    var engine: FilterEngine? = null
        private set

    private val statusFlow = MutableStateFlow(Status())
    val status: StateFlow<Status> = statusFlow.asStateFlow()

    private val store = FilterListsStore(File(dir, LISTS_FILE), language)
    private val engineFile = File(dir, ENGINE_FILE)
    private val listsDir = File(dir, LISTS_DIR)
    private val psl by lazy(loadPsl)
    /** Everything that reads or writes the store and the files */
    private val lock = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    @Volatile
    private var started = false
    private var updating = false

    /**
     * Loads the engine (or builds it from the lists if its file is missing or older), then, if
     * [scheduleUpdates], updates the lists now and then while the process lives
     */
    fun start(scheduleUpdates: Boolean) {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            initialize()
            if (!scheduleUpdates) return@launch
            //the first start downloads at once; later ones let the app and its pages start first
            val missing = lock.withLock { store.enabledSources().any { !listFile(it.id).exists() } }
            if (!missing) delay(START_DELAY_MS)
            while (true) {
                update(force = false)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Loads the engine from its file, or builds it again from the lists when the file does not fit them */
    suspend fun initialize() {
        var rebuild = false
        try {
            lock.withLock {
                migrateFromNativeLibrary()
                store.load()
                rebuild = !loadEngine()
                publish()
            }
        } finally {
            loaded.complete(Unit)
        }
        if (rebuild) lock.withLock { rebuild() }
    }

    /**
     * Waits for the engine file to be loaded (not for a build), at most [timeoutMs]: tabs should not start
     * loading before. Returns at once when the service was not started.
     */
    suspend fun awaitLoaded(timeoutMs: Long) {
        if (!started) return
        withTimeoutOrNull(timeoutMs) { loaded.await() }
    }

    /** Downloads the lists that are due (all enabled ones if [force]); false if a download failed */
    suspend fun update(force: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { updateLocked(force, only = null) }
    }

    fun setListEnabled(id: String, enabled: Boolean): Job = launchLocked {
        val changed = if (enabled) store.enabled.add(id) else store.enabled.remove(id)
        if (!changed) return@launchLocked
        store.save()
        if (enabled && !listFile(id).exists()) updateLocked(force = false, only = id) else rebuild()
    }

    /** Turns on the regional list [id] (null: none) and off the other regional ones */
    fun setRegionalList(id: String?): Job = launchLocked {
        val regional = FilterListCatalog.regional.map { it.id }.toSet()
        val before = store.enabled.toSet()
        store.enabled.removeAll(regional)
        if (id != null && id in regional) store.enabled += id
        if (store.enabled == before) return@launchLocked
        store.save()
        if (id != null && !listFile(id).exists()) updateLocked(force = false, only = id) else rebuild()
    }

    /** The address of the user's own list; "" for none */
    fun setCustomUrl(url: String): Job = launchLocked {
        val trimmed = url.trim()
        if (trimmed == store.customUrl) return@launchLocked
        store.customUrl = trimmed
        store.forget(FilterListCatalog.CUSTOM)
        listFile(FilterListCatalog.CUSTOM).delete()
        store.save()
        if (trimmed.isNotEmpty()) updateLocked(force = true, only = FilterListCatalog.CUSTOM) else rebuild()
    }

    private fun launchLocked(block: suspend () -> Unit): Job = scope.launch(Dispatchers.IO) {
        loaded.await()
        lock.withLock { block() }
    }

    // --- Under the lock ---

    /** @return true when the engine of the file fits the lists, or there are no lists to build one of */
    private fun loadEngine(): Boolean {
        if (availableSources().isEmpty()) {
            engine = null
            return true
        }
        if (!engineFile.exists()) return false
        return try {
            val loaded = FilterEngine.load(engineFile, psl)
            if (loaded.fingerprint != fingerprint()) return false
            engine = loaded
            true
        } catch (e: AdblockFormatException) {
            Log.w(TAG, "The engine file can not be used: ${e.message}")
            engineFile.delete()
            false
        } catch (e: IOException) {
            Log.e(TAG, "Can not read the engine file", e)
            false
        }
    }

    private fun updateLocked(force: Boolean, only: String?): Boolean {
        setUpdating(true)
        var ok = true
        var downloaded = false
        try {
            for (source in store.enabledSources()) {
                if (only != null && source.id != only) continue
                val meta = store.meta(source.id)
                val file = listFile(source.id)
                if (!force && !isDue(meta, file.exists())) continue
                val temp = File(listsDir, source.id + ".txt.tmp")
                try {
                    listsDir.mkdirs()
                    val result = fetcher.fetch(source.url, meta.etag.takeIf { file.exists() },
                        meta.lastModified.takeIf { file.exists() }, temp)
                    if (result is ListFetcher.Result.Downloaded) {
                        checkList(temp)
                        replace(temp, file)
                        meta.etag = result.etag
                        meta.lastModified = result.lastModified
                        downloaded = true
                    }
                    meta.lastSuccess = now()
                    meta.failures = 0
                } catch (e: IOException) {
                    Log.w(TAG, "Can not update ${source.id}: $e")
                    temp.delete()
                    meta.failures++
                    meta.lastAttempt = now()
                    ok = false
                }
                store.save()
            }
            if (downloaded || engine?.fingerprint != fingerprint()) rebuild()
        } finally {
            setUpdating(false)
        }
        return ok
    }

    /** Whether the list should be downloaded now: its time came (or it is missing), and not too soon after a failure */
    private fun isDue(meta: ListMeta, exists: Boolean): Boolean {
        val time = now()
        if (meta.failures > 0 && time - meta.lastAttempt < RETRY_DELAYS_MS[minOf(meta.failures, RETRY_DELAYS_MS.size) - 1]) {
            return false
        }
        if (!exists) return true
        val hours = (meta.expiresHours ?: DEFAULT_INTERVAL_HOURS).coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
        return time - meta.lastSuccess >= hours * HOUR_MS
    }

    /** A downloaded file must look like a list, not like the error page of a portal or a server */
    private fun checkList(file: File) {
        if (file.length() == 0L) throw IOException("empty list")
        val start = file.inputStream().use { input ->
            val bytes = ByteArray(512)
            val read = input.read(bytes)
            if (read <= 0) "" else String(bytes, 0, read)
        }
        if (start.trimStart().startsWith("<")) throw IOException("not a filter list (HTML)")
    }

    private fun replace(temp: File, file: File) {
        if (temp.renameTo(file)) return
        file.delete()
        if (!temp.renameTo(file)) throw IOException("can not rename $temp")
    }

    private fun rebuild() {
        val sources = availableSources()
        if (sources.isEmpty()) {
            engine = null
            engineFile.delete()
            publish()
            return
        }
        val tid = Process.myTid()
        val priority = Process.getThreadPriority(tid)
        try {
            //the pages being loaded come first on weak TVs
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val compiler = FilterCompiler()
            for (source in sources) {
                val stats = listFile(source.id).bufferedReader().useLines { compiler.addList(it) }
                val meta = store.meta(source.id)
                meta.filters = stats.network
                meta.skipped = stats.skippedTotal
                meta.expiresHours = stats.expiresHours
            }
            val built = compiler.build(psl, fingerprint())
            try {
                built.writeTo(engineFile)
            } catch (e: IOException) {
                //the engine still works until the process ends
                Log.e(TAG, "Can not write the engine file", e)
            }
            engine = built
            store.save()
            Log.i(TAG, "Engine built: ${built.filterCount} filters of ${sources.map { it.id }}")
        } catch (e: Exception) {
            Log.e(TAG, "Can not build the engine", e)
        } finally {
            Process.setThreadPriority(tid, priority)
        }
        publish()
    }

    /** The enabled lists that were downloaded */
    private fun availableSources() = store.enabledSources().filter { listFile(it.id).exists() }

    /** Of the compiler and of the files of the lists the engine is built of */
    private fun fingerprint(): Long {
        val key = StringBuilder("compiler ").append(FilterCompiler.VERSION)
        for (source in availableSources()) {
            val file = listFile(source.id)
            key.append('|').append(source.id).append(' ').append(source.url).append(' ').append(file.length())
                .append(' ').append(file.lastModified())
        }
        val text = key.toString()
        val crc = CRC32()
        crc.update(text.toByteArray())
        return (text.hashCode().toLong() shl 32) xor crc.value
    }

    private fun listFile(id: String) = File(listsDir, "$id.txt")

    private fun setUpdating(value: Boolean) {
        updating = value
        publish()
    }

    private fun publish() {
        val enabled = store.enabledSources()
        val lists = FilterListCatalog.lists.map { info ->
            val meta = store.meta(info.id)
            ListState(info.id, info.title, info.id in store.enabled, meta.filters, meta.skipped, meta.lastSuccess,
                meta.failures > 0)
        }.toMutableList()
        if (store.customUrl.isNotEmpty()) {
            val meta = store.meta(FilterListCatalog.CUSTOM)
            lists += ListState(FilterListCatalog.CUSTOM, store.customUrl, true, meta.filters, meta.skipped,
                meta.lastSuccess, meta.failures > 0)
        }
        val available = enabled.filter { listFile(it.id).exists() }.map { store.meta(it.id) }
        statusFlow.value = Status(
            loaded = loaded.isCompleted, updating = updating, lists = lists, customUrl = store.customUrl,
            filters = engine?.filterCount ?: 0, skipped = available.sumOf { it.skipped },
            lastUpdate = enabled.maxOfOrNull { store.meta(it.id).lastSuccess } ?: 0,
        )
    }

    /** The files and settings of the native library this replaced */
    private fun migrateFromNativeLibrary() {
        dir.parentFile?.let { File(it, OLD_ENGINE_FILE).delete() }
        val prefs = config.prefs
        if (prefs.contains(OLD_LIST_URL_KEY) || prefs.contains(OLD_LAST_UPDATE_KEY)) {
            prefs.edit().remove(OLD_LIST_URL_KEY).remove(OLD_LAST_UPDATE_KEY).apply()
        }
    }

    companion object {
        private const val TAG = "AdBlockService"
        const val LISTS_FILE = "lists.json"
        const val ENGINE_FILE = "engine.bin"
        const val LISTS_DIR = "lists"
        const val OLD_ENGINE_FILE = "adblock_ser.dat"
        const val OLD_LIST_URL_KEY = "adblock_list_url"
        const val OLD_LAST_UPDATE_KEY = "adblock_last_update"

        private const val HOUR_MS = 60 * 60 * 1000L
        private const val START_DELAY_MS = 30_000L
        private const val CHECK_INTERVAL_MS = 6 * HOUR_MS
        private const val DEFAULT_INTERVAL_HOURS = 4 * 24
        private const val MIN_INTERVAL_HOURS = 24
        private const val MAX_INTERVAL_HOURS = 7 * 24
        /** After the first, second and further failures in a row */
        private val RETRY_DELAYS_MS = longArrayOf(HOUR_MS, 6 * HOUR_MS, 24 * HOUR_MS)
    }
}
