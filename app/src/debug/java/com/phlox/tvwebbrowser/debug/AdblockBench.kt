package com.phlox.tvwebbrowser.debug

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.util.Locale

/** An ad blocker the benchmark measures */
interface AdblockBenchTarget {
    val name: String
    /** Builds the filters from the [lists] and stores them in [dir] */
    fun build(lists: List<File>, dir: File)
    /** Loads what [build] stored in [dir] */
    fun load(dir: File)
    fun isAd(url: Uri, type: String?, baseUri: Uri): Boolean
    /** Frees what [build] or [load] made */
    fun release()
    /** What the target can tell about itself after [load] */
    fun describe(): String? = null
}

/** One request of the corpus: a line `url<TAB>type<TAB>document url` of requests.tsv (WebExtension types) */
class BenchRequest(val url: Uri, val type: String?, val documentUrl: Uri)

/**
 * Measures the [targets] on the filter lists and requests of [dir]: the time and memory of building the
 * filters into [outDir] (internal storage, as the app keeps them: /sdcard is slow FUSE on Android 11+),
 * the size of what is stored, the time and memory of loading it and the time of each check. Then compares the decisions of the first two targets (`diff.log` next to the inputs has the requests
 * they decide differently).
 */
class AdblockBench(private val dir: File, private val outDir: File, private val log: (String) -> Unit) {
    private val lists = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }

    fun run(targets: List<AdblockBenchTarget>, passes: Int = 3) {
        log("lists: " + lists.joinToString { "${it.name} (${it.length() / 1024} KB)" })
        require(lists.isNotEmpty()) { "no *.txt filter lists in $dir" }
        val requests = readRequests()
        log("requests: ${requests.size}")
        val decisions = ArrayList<Pair<String, BooleanArray>>()
        for (target in targets) {
            try {
                runTarget(target, requests, passes)?.let { decisions += target.name to it }
            } catch (e: Throwable) {
                log("${target.name}: failed: $e")
            } finally {
                target.release()
            }
        }
        if (decisions.size >= 2) compare(requests, decisions[0], decisions[1])
    }

    private fun readRequests(): List<BenchRequest> {
        val file = File(dir, "requests.tsv")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            val url = Uri.parse(parts[0])
            val document = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) } ?: url
            //parsed here, outside the measured checks (the engines give parsed urls)
            url.host
            document.host
            BenchRequest(url, parts.getOrNull(1)?.takeIf { it.isNotEmpty() }, document)
        }
    }

    /** Measures the [target]; returns its decision for each request */
    private fun runTarget(target: AdblockBenchTarget, requests: List<BenchRequest>, passes: Int): BooleanArray? {
        val out = File(outDir, target.name.replace(Regex("[^A-Za-z0-9]+"), "_"))
        out.deleteRecursively()
        out.mkdirs()
        log("== ${target.name}")

        var before = Memory.snapshot()
        var start = SystemClock.elapsedRealtimeNanos()
        target.build(lists, out)
        val buildMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        log("build: %.0f ms, memory after: %s".format(Locale.US, buildMs, Memory.snapshot().minus(before)))
        log("stored: %d KB".format(Locale.US, out.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024))
        target.release()

        before = Memory.snapshot()
        start = SystemClock.elapsedRealtimeNanos()
        target.load(out)
        val loadMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        log("load: %.1f ms, memory: %s".format(Locale.US, loadMs, Memory.snapshot().minus(before)))
        target.describe()?.let(log)

        if (requests.isEmpty()) return null
        //a pass for the JIT, then the measured ones
        val decisions = BooleanArray(requests.size) { target.isAd(requests[it].url, requests[it].type, requests[it].documentUrl) }
        val times = LongArray(requests.size * passes)
        var i = 0
        var blocked = 0
        repeat(passes) {
            blocked = 0
            for (request in requests) {
                val t = System.nanoTime()
                val ad = target.isAd(request.url, request.type, request.documentUrl)
                times[i++] = System.nanoTime() - t
                if (ad) blocked++
            }
        }
        times.sort()
        fun us(p: Double) = times[minOf(times.size - 1, (times.size * p).toInt())] / 1000.0
        log("check (µs): p50 %.1f, p90 %.1f, p99 %.1f, max %.1f, mean %.1f; blocked %d of %d".format(Locale.US,
            us(0.5), us(0.9), us(0.99), times.last() / 1000.0, times.average() / 1000.0, blocked, requests.size))
        return decisions
    }

    private fun compare(requests: List<BenchRequest>, a: Pair<String, BooleanArray>, b: Pair<String, BooleanArray>) {
        var both = 0
        val onlyA = ArrayList<Int>()
        val onlyB = ArrayList<Int>()
        for (i in requests.indices) {
            when {
                a.second[i] && b.second[i] -> both++
                a.second[i] -> onlyA += i
                b.second[i] -> onlyB += i
            }
        }
        log("== compare: both block $both, only ${a.first} ${onlyA.size}, only ${b.first} ${onlyB.size}")
        File(dir, "diff.log").printWriter().use { out ->
            for ((name, indices) in listOf(a.first to onlyA, b.first to onlyB)) {
                out.println("# blocked only by $name: ${indices.size}")
                for (i in indices) out.println("${requests[i].url}\t${requests[i].type}\t${requests[i].documentUrl}")
            }
        }
    }
}

/** The memory of the process after a garbage collection: the Java heap, the native heap and the PSS */
data class Memory(val javaKb: Long, val nativeKb: Long, val pssKb: Long) {
    fun minus(other: Memory) = Memory(javaKb - other.javaKb, nativeKb - other.nativeKb, pssKb - other.pssKb)

    override fun toString() = "java %+d KB, native %+d KB, pss %+d KB".format(Locale.US, javaKb, nativeKb, pssKb)

    companion object {
        fun snapshot(): Memory {
            repeat(2) {
                Runtime.getRuntime().gc()
                System.runFinalization()
                SystemClock.sleep(100)
            }
            val runtime = Runtime.getRuntime()
            return Memory((runtime.totalMemory() - runtime.freeMemory()) / 1024,
                Debug.getNativeHeapAllocatedSize() / 1024, Debug.getPss())
        }
    }
}
