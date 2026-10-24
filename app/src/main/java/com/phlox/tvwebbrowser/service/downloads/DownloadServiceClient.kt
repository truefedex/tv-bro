package com.phlox.tvwebbrowser.service.downloads

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.os.BundleCompat

/**
 * The connection of a browser process to [DownloadService], which runs in its own process.
 * The browser activity keeps it bound ([acquire]/[release]) while it is started and a download is in progress
 * or a downloads screen is shown ([DownloadServiceNeed]); a row a dead process left in progress counts, so
 * the service starts and marks it interrupted. Commands sent while it is not bound wait, and the service is
 * bound until they are delivered, whatever the screens do meanwhile.
 */
@MainThread
class DownloadServiceClient(private val context: Context) {
    companion object {
        private const val TAG = "DownloadServiceClient"
    }

    private var users = 0
    private var bound = false
    private var messenger: Messenger? = null
    private val pendingCommands = ArrayList<Message>()
    private val pendingActions = ArrayList<() -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            messenger = Messenger(binder)
            val actions = pendingActions.toList()
            pendingActions.clear()
            actions.forEach { it() }
            val commands = pendingCommands.toList()
            pendingCommands.clear()
            commands.forEach { send(it) }
            unbindIfUnused()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            messenger = null
        }
    }

    fun acquire() {
        users++
        bind()
    }

    fun release() {
        users--
        unbindIfUnused()
    }

    /**
     * Runs the [action] once the service is bound (at once if it is). The service has then checked the
     * downloads a dead process left: a row inserted from now on is not taken for one of them. Keep the
     * service bound ([acquire]) until the work the action starts is done.
     */
    fun whenConnected(action: () -> Unit) {
        if (messenger != null) {
            action()
            return
        }
        pendingActions.add(action)
        bind()
    }

    fun send(message: Message) {
        val messenger = messenger
        if (messenger == null) {
            pendingCommands.add(message)
            bind()
            return
        }
        try {
            messenger.send(message)
        } catch (e: RemoteException) {
            Log.e(TAG, "The download service is gone", e)
            this.messenger = null
            pendingCommands.add(message)
            return
        }
        //the service got its own copy of the pipe
        BundleCompat.getParcelable(message.data, DownloadCommands.KEY_STREAM, ParcelFileDescriptor::class.java)
            ?.let { closeSilently(it) }
    }

    /**
     * Closes the copy of this process of the read end of a reliable pipe. A plain close would tell
     * the writer that the reader is gone, and the writer would not leave the status of its end
     * (complete or failed) for the reader in the service.
     */
    private fun closeSilently(readEnd: ParcelFileDescriptor) {
        val parcel = Parcel.obtain()
        try {
            //writing it as a return value closes it without a status
            readEnd.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
        } finally {
            parcel.recycle()
        }
    }

    private fun bind() {
        if (bound) return
        bound = context.bindService(Intent(context, DownloadService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindIfUnused() {
        if (!bound || users > 0 || pendingCommands.isNotEmpty() || pendingActions.isNotEmpty()) return
        context.unbindService(connection)
        bound = false
        messenger = null
    }
}
