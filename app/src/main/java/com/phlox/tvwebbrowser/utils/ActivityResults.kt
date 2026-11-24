package com.phlox.tvwebbrowser.utils

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import java.util.concurrent.atomic.AtomicInteger

private val resultKeys = AtomicInteger()

/** The results the browser activity waits for: it left the screen for a picker of its own, not for good */
object ActivityResults {
    private val pending = AtomicInteger()

    val awaited: Boolean get() = pending.get() > 0

    /** A new activity gets none of the results asked before */
    fun reset() = pending.set(0)

    internal fun onLaunched() = pending.incrementAndGet()

    internal fun onResult() {
        pending.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}

/**
 * Launches [contract] with [input] and delivers its result once to [onResult].
 * Unlike registerForActivityResult it can be called at any moment of the activity lifecycle;
 * the result is lost if the activity is recreated before it arrives.
 * Exceptions of the launch (for example ActivityNotFoundException) are thrown to the caller.
 */
fun <I, O> ComponentActivity.launchForResult(
    contract: ActivityResultContract<I, O>,
    input: I,
    onResult: (O) -> Unit
) {
    var launcher: ActivityResultLauncher<I>? = null
    launcher = activityResultRegistry.register("tvbro_result_" + resultKeys.incrementAndGet(), contract) { result ->
        launcher?.unregister()
        ActivityResults.onResult()
        onResult(result)
    }
    try {
        ActivityResults.onLaunched()
        launcher.launch(input)
    } catch (e: Exception) {
        ActivityResults.onResult()
        launcher.unregister()
        throw e
    }
}
