package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.phlox.tvwebbrowser.browser.toDownload
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.webengine.DownloadRequest

/**
 * Starts downloads in [DownloadService] on behalf of the [activity]: asks for the needed
 * permissions first and keeps the service bound while the activity is started.
 */
class DownloadLauncher(
    private val activity: Activity,
    private val onDownloadStarted: (Download) -> Unit
) {
    companion object {
        private const val TAG = "DownloadLauncher"
        const val PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS = 10004
        const val PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS = 10003
    }

    private var service: DownloadService? = null
    /** Waits for the permissions */
    private var pendingDownload: Download? = null
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val downloadServiceBinder = binder as? DownloadService.Binder
            if (downloadServiceBinder == null) {
                Log.e(TAG, "Download service connection failed")
                //probably service still in another process due to incognito mode process switch
                //so we will try to reconnect in a few seconds
                handler.postDelayed({
                    activity.bindService(Intent(activity, DownloadService::class.java),
                        this, Context.BIND_AUTO_CREATE)
                }, 1000)
                return
            }
            service = downloadServiceBinder.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bindService() {
        activity.bindService(Intent(activity, DownloadService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        activity.unbindService(connection)
        service = null
    }

    fun download(request: DownloadRequest) {
        pendingDownload = request.toDownload()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS)
        } else {
            startPendingDownload()
        }
    }

    /**
     * @return true if the result was for a permission asked by this launcher
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS &&
            requestCode != PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS) {
            return false
        }
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPendingDownload()
        }
        return true
    }

    private fun startPendingDownload() {
        val download = pendingDownload ?: return
        pendingDownload = null
        service?.startDownload(download)
        onDownloadStarted(download)
    }
}
