package com.phlox.tvwebbrowser.player

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges engine-side media detection to the UI so the toolbar badge can show a
 * live count of playable streams. Both engines call [notifyChanged] right after
 * they record a candidate; the listener is invoked on whatever thread detected it.
 */
object MediaDiscoveryNotifier {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    fun notifyChanged() { listeners.forEach { runCatching { it() } } }
}
