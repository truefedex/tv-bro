package com.phlox.tvwebbrowser.utils

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBackInvokedCallbackTest {
    /** The back callbacks of a window: Back goes to the one of the highest priority, the last registered of equals */
    private class FakeDispatcher : OnBackInvokedDispatcher {
        val callbacks = mutableListOf<Pair<Int, OnBackInvokedCallback>>()

        override fun registerOnBackInvokedCallback(priority: Int, callback: OnBackInvokedCallback) {
            callbacks.removeAll { it.second === callback }
            callbacks += priority to callback
        }

        override fun unregisterOnBackInvokedCallback(callback: OnBackInvokedCallback) {
            callbacks.removeAll { it.second === callback }
        }

        fun back() {
            val top = callbacks.maxOfOrNull { it.first } ?: return
            callbacks.last { it.first == top }.second.onBackInvoked()
        }
    }

    private val dispatcher = FakeDispatcher()
    private val backs = mutableListOf<String>()
    private val overlayBack = OverlayBackInvokedCallback(dispatcher) { backs += "screen" }

    /** The activity registers its callback first; WebView registers one for its fullscreen view when a page goes fullscreen */
    private fun registerActivityAndFullscreenView() {
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { backs += "activity" }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { backs += "fullscreen view" }
    }

    @Test
    fun takesBackBeforeTheFullscreenViewOfTheEngine() {
        overlayBack.setRegistered(true)
        registerActivityAndFullscreenView()
        dispatcher.back()
        assertEquals(listOf("screen"), backs)
    }

    @Test
    fun unregisteredItLeavesBackToTheOthers() {
        registerActivityAndFullscreenView()
        overlayBack.setRegistered(true)
        overlayBack.setRegistered(false)
        dispatcher.back()
        assertEquals(listOf("fullscreen view"), backs)
    }

    @Test
    fun repeatedStatesChangeNothing() {
        overlayBack.setRegistered(true)
        overlayBack.setRegistered(true)
        assertEquals(1, dispatcher.callbacks.size)
        assertTrue(overlayBack.isRegistered)
        //WebView reports the end of fullscreen twice when the app leaves it
        overlayBack.setRegistered(false)
        overlayBack.setRegistered(false)
        assertTrue(dispatcher.callbacks.isEmpty())
        assertFalse(overlayBack.isRegistered)
    }
}
