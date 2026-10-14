package com.phlox.tvwebbrowser.utils

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import java.util.concurrent.atomic.AtomicInteger

private val resultKeys = AtomicInteger()

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
        onResult(result)
    }
    try {
        launcher.launch(input)
    } catch (e: Exception) {
        launcher.unregister()
        throw e
    }
}
