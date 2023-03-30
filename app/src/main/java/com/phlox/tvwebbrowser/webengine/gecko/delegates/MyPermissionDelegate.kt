package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission

class MyPermissionDelegate(private val webEngine: GeckoWebEngine): PermissionDelegate {
    private val permissionsRequests = HashMap<Int, PermissionDelegate.Callback>()//request code, callback

    override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<String>?,
        callback: PermissionDelegate.Callback) {
        if (permissions == null) {
            callback.reject()
            return
        }
        val requestCode = webEngine.callback?.requestPermissions(permissions) ?: return
        permissionsRequests[requestCode] = callback
    }

    override fun onContentPermissionRequest(session: GeckoSession,
        perm: ContentPermission): GeckoResult<Int>? {
        val activity = webEngine.callback?.getActivity() ?: return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        val resId: Int = when (perm.permission) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> R.string.request_geolocation
            PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> R.string.request_notification
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> R.string.request_storage
            PermissionDelegate.PERMISSION_XR -> R.string.request_xr
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                return if (!TVBro.config.allowAutoplayMedia) {
                GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            } else {
                GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            }
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> R.string.request_media_key_system_access
            PermissionDelegate.PERMISSION_STORAGE_ACCESS -> R.string.request_storage_access
            else -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }

        val title: String = activity.getString(resId, Uri.parse(perm.uri).authority)
        return webEngine.promptDelegate.onPermissionPrompt(session, title, perm)
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<PermissionDelegate.MediaSource>?,
        audio: Array<PermissionDelegate.MediaSource>?,
        callback: PermissionDelegate.MediaCallback
    ) {
        // If we don't have device permissions at this point, just automatically reject the request
        // as we will have already have requested device permissions before getting to this point
        // and if we've reached here and we don't have permissions then that means that the user
        // denied them.

        // If we don't have device permissions at this point, just automatically reject the request
        // as we will have already have requested device permissions before getting to this point
        // and if we've reached here and we don't have permissions then that means that the user
        // denied them.
        val activity = webEngine.callback?.getActivity()
        if (activity == null) {
            callback.reject()
            return
        }
        if ((audio != null && ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED)
            || (video != null && ContextCompat.checkSelfPermission(
                activity, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED)
        ) {
            callback.reject()
            return
        }

        val host = Uri.parse(uri).authority
        val title: String = if (audio == null) {
            activity.getString(R.string.request_video, host)
        } else if (video == null) {
            activity.getString(R.string.request_audio, host)
        } else {
            activity.getString(R.string.request_media, host)
        }

        val videoNames = normalizeMediaName(video)
        val audioNames = normalizeMediaName(audio)

        webEngine.promptDelegate.onMediaPrompt(session, title, video, audio, videoNames, audioNames, callback)
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        val cb = permissionsRequests.remove(requestCode) ?: return false
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                // At least one permission was not granted.
                cb.reject()
                return true
            }
        }
        cb.grant()
        return true
    }

    private fun normalizeMediaName(sources: Array<PermissionDelegate.MediaSource>?): Array<String>? {
        if (sources == null) {
            return null
        }
        val context = webEngine.callback?.getActivity() ?: return null
        val res = arrayOfNulls<String>(sources.size)
        for (i in sources.indices) {
            val mediaSource = sources[i].source
            val name = sources[i].name
            if (PermissionDelegate.MediaSource.SOURCE_CAMERA == mediaSource) {
                if (name!!.lowercase().contains("front")) {
                    res[i] = context.getString(R.string.media_front_camera)
                } else {
                    res[i] = context.getString(R.string.media_back_camera)
                }
            } else if (!name!!.isEmpty()) {
                res[i] = name
            } else if (PermissionDelegate.MediaSource.SOURCE_MICROPHONE == mediaSource) {
                res[i] = context.getString(R.string.media_microphone)
            } else {
                res[i] = context.getString(R.string.media_other)
            }
        }
        return res.requireNoNulls()
    }
}