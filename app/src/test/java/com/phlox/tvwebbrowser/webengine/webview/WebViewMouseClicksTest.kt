package com.phlox.tvwebbrowser.webengine.webview

import com.phlox.tvwebbrowser.widgets.cursor.MouseClicks
import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewMouseClicksTest {
    @Test
    fun newWebViewTakesTheButtonFromTheButtonState() {
        assertEquals(MouseClicks.BUTTON_STATE, WebViewMouseClicks.choose(true) { 137 })
        assertEquals(MouseClicks.BUTTON_STATE, WebViewMouseClicks.choose(false) { 140 })
    }

    @Test
    fun oldOrUnknownWebViewGetsTheActionButtonWhereItCanBeSet() {
        assertEquals(MouseClicks.ACTION_BUTTON, WebViewMouseClicks.choose(true) { 58 })
        assertEquals(MouseClicks.ACTION_BUTTON, WebViewMouseClicks.choose(true) { null })
    }

    @Test
    fun elseOldOrUnknownWebViewClicksAsAFinger() {
        assertEquals(MouseClicks.TOUCH, WebViewMouseClicks.choose(false) { 136 })
        assertEquals(MouseClicks.TOUCH, WebViewMouseClicks.choose(false) { null })
    }
}
