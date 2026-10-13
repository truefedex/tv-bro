package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

// This is a leftover from the removal of the abandoned "text selection" feature.
// I'll leave it here for now as a framework for future extension-based features.
class AppContentScriptPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        //Log.d(TAG, "onPortMessage: $message")
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        webEngine.appContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppContentScriptPortDelegate::class.java.simpleName
    }
}