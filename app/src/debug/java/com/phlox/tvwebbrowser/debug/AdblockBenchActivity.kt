package com.phlox.tvwebbrowser.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Debug builds only: measures the ad blockers ([AdblockBench]) on the filter lists (`*.txt`) and the requests
 * (`requests.tsv`) put into `<external files dir>/adblock-bench`, for example:
 * ```
 * adb push easylist.txt easyprivacy.txt requests.tsv /sdcard/Android/data/<package>/files/adblock-bench/
 * adb shell am start -n <package>/com.phlox.tvwebbrowser.debug.AdblockBenchActivity
 * ```
 * The results go to logcat (tag AdblockBench), to the screen and to `results-<time>.log` next to the inputs.
 */
class AdblockBenchActivity : Activity() {
    private lateinit var text: TextView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(48, 48, 48, 48) }
        setContentView(ScrollView(this).apply { addView(text) })
        if (running) return
        running = true
        val dir = File(getExternalFilesDir(null), "adblock-bench").apply { mkdirs() }
        val results = File(dir, "results-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log")
        val log = { line: String ->
            Log.i(TAG, line)
            results.appendText(line + "\n")
            runOnUiThread { text.append(line + "\n") }
        }
        thread(name = "AdblockBench") {
            try {
                AdblockBench(dir, File(filesDir, "adblock-bench"), log).run(listOf(NativeAdBlockBenchTarget(), KotlinAdBlockBenchTarget(assets)))
                log("done")
            } catch (e: Throwable) {
                Log.e(TAG, "benchmark failed", e)
                log("failed: $e")
            }
        }
    }

    companion object {
        const val TAG = "AdblockBench"
    }
}
