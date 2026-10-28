package com.phlox.tvwebbrowser.webengine.webview

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest

/**
 * Answers the requests of pages for the camera, the microphone and other resources of [PermissionRequest]
 * (the protected media id is answered by [WebViewEx] itself), one dialog at a time: requests that come while
 * the dialog of another one is shown wait in a queue. WebView remembers no answer, and pages often ask more
 * than once (each `getUserMedia` call is a request), so the resources the user allowed to an origin are kept in
 * [granted] until the process ends and later requests of that origin for them are granted without asking.
 * Used on the main thread only.
 */
class WebViewPagePermissions(private val host: Host) {
    interface Host {
        fun hasAndroidPermission(permission: String): Boolean
        /** Asks for Android permissions; the answer goes to [onAndroidPermissionsResult] */
        fun requestAndroidPermissions(permissions: Array<String>)
        /**
         * Shows the dialog asking the user about [resources], calling [onAnswer] with true for Allow.
         * @return a function that closes the dialog without an answer, or null if no dialog could be shown
         */
        fun showDialog(resources: Array<String>, onAnswer: (Boolean) -> Unit): (() -> Unit)?
    }

    companion object {
        /** The resources the user allowed, by origin; for the life of the process, incognito has its own */
        private val granted = HashMap<String, MutableSet<String>>()

        internal fun clearGrantsForTests() = granted.clear()

        private fun androidPermissionOf(resource: String): String? = when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            else -> null
        }
    }

    private val queue = ArrayDeque<PermissionRequest>()
    /** The request whose dialog is shown or whose Android permissions are asked */
    private var current: PermissionRequest? = null
    private var dismissDialog: (() -> Unit)? = null

    fun onRequest(request: PermissionRequest) {
        if (grantIfRemembered(request)) return
        queue.addLast(request)
        if (current == null) showNext()
    }

    fun onRequestCanceled(request: PermissionRequest) {
        if (request === current) {
            dismissDialog?.invoke()
            finishCurrent()
        } else {
            queue.remove(request)
        }
    }

    fun onAndroidPermissionsResult(permissions: Array<String>, grantResults: IntArray) {
        val request = current ?: return
        if (dismissDialog != null) return // the dialog is still shown: not the answer for this request
        val resources = request.resources.filter { resource ->
            val permission = androidPermissionOf(resource) ?: return@filter true
            val i = permissions.indexOf(permission)
            if (i >= 0 && i < grantResults.size) grantResults[i] == PackageManager.PERMISSION_GRANTED
            else host.hasAndroidPermission(permission)
        }
        if (resources.isEmpty()) {
            request.deny()
        } else {
            grant(request, resources.toTypedArray())
        }
        finishCurrent()
    }

    private fun showNext() {
        while (current == null) {
            val request = queue.removeFirstOrNull() ?: return
            // the user may have allowed what it asks while it waited
            if (grantIfRemembered(request)) continue
            current = request
            var answered = false
            val dismiss = host.showDialog(request.resources) { allow ->
                answered = true
                dismissDialog = null
                onAnswer(request, allow)
            }
            if (dismiss == null) {
                current = null
                request.deny()
            } else if (!answered) {
                dismissDialog = dismiss
            }
        }
    }

    private fun onAnswer(request: PermissionRequest, allow: Boolean) {
        if (request !== current) return
        if (!allow) {
            request.deny()
            finishCurrent()
            return
        }
        val needed = missingAndroidPermissions(request.resources)
        if (needed.isEmpty()) {
            grant(request, request.resources)
            finishCurrent()
        } else {
            // the request is answered when the Android permissions result comes
            host.requestAndroidPermissions(needed.toTypedArray())
        }
    }

    private fun finishCurrent() {
        current = null
        dismissDialog = null
        showNext()
    }

    private fun grant(request: PermissionRequest, resources: Array<String>) {
        granted.getOrPut(request.origin.toString()) { HashSet() }.addAll(resources)
        request.grant(resources)
    }

    private fun grantIfRemembered(request: PermissionRequest): Boolean {
        val remembered = granted[request.origin.toString()] ?: return false
        if (!remembered.containsAll(request.resources.asList())) return false
        if (missingAndroidPermissions(request.resources).isNotEmpty()) return false
        request.grant(request.resources)
        return true
    }

    private fun missingAndroidPermissions(resources: Array<String>): List<String> =
        resources.mapNotNull { androidPermissionOf(it) }.distinct().filter { !host.hasAndroidPermission(it) }
}
