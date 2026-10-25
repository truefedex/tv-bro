package com.phlox.tvwebbrowser.sync

import android.util.Log
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.model.PairedDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The server of a TV: paired phones connect to it to open tabs and to send bookmarks and files, a new phone pairs
 * with the secret of the QR code while it is shown ([startPairing]). It listens while the browser is on the
 * screen ([start], [stop]), on the same port each time if it is free.
 *
 * Each connection is a session of a phone: its [DeviceInfo], then requests until the phone closes it.
 * Sockets are read on background threads; [Host] is called on the main thread.
 */
class SyncServer(
    private val settings: SyncSettings,
    private val data: () -> DataRepositories,
    /** The bookmarks of the TV, which phones change */
    private val bookmarks: LocalBookmarks,
    private val host: Host,
    private val appVersion: Int = BuildConfig.VERSION_CODE,
    /** All interfaces; tests listen on the loopback only */
    private val bindAddress: InetAddress? = null,
    /** Advertises the server in the network (none in tests) */
    private val discovery: SyncDiscovery? = null,
    /** The thread of [Host]: the main one; tests use their own, so they do not set Dispatchers.Main */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    companion object {
        private const val TAG = "SyncServer"
        private const val HANDSHAKE_TIMEOUT = 15_000
        /** A phone keeps its session while its sync page is open */
        private const val IDLE_TIMEOUT = 120_000
        /** How long the download of a file that came whole may take to be saved */
        private const val SAVE_TIMEOUT = 30_000L
        /** How long a file that could not be written takes to tell why (cancelled by the user, or not) */
        private const val FAILURE_TIMEOUT = 5_000L
        /** How long the bytes a phone still sends after an error are read, so it gets the error before the connection is reset */
        private const val DRAIN_TIMEOUT = 3_000
    }

    interface Host {
        fun openTabs(from: PairedDevice, tabs: List<SyncTab>)
        fun onBookmarksReplaced(from: PairedDevice, changes: BookmarksReplacer.Changes)
        fun onPaired(device: PairedDevice)
        /** The phone asked to be forgotten (its pairing is deleted) */
        fun onForgotten(device: PairedDevice)
        /**
         * Starts saving a file the phone sends (its [name] is safe already, see [SyncRequests.safeFileName])
         * @param size -1 if not known
         * @throws SyncException with the code to answer when the TV can not take it now
         */
        suspend fun receiveFile(from: PairedDevice, name: String, mimeType: String?, size: Long): IncomingFile
    }

    /** Where a file a phone sends goes */
    interface IncomingFile {
        /** The bytes of the file; closed by the server when all came */
        val output: OutputStream
        /** The file did not come whole: what was written is dropped (the output is closed too) */
        fun fail(reason: String)
        /**
         * Waits until the file is saved, after [output] was closed or the file failed
         * @return the name the file was saved with
         * @throws SyncException [SyncRequests.CANCELLED], [SyncRequests.STORAGE] or [SyncRequests.FAILED]
         */
        suspend fun awaitSaved(): String
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    /** The connections of the sessions: cancelling a coroutine does not end a blocked read, closing its socket does */
    private val sessionSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile
    private var destroyed = false

    @Volatile
    private var pairingSecret: ByteArray? = null
    private val _pairingLink = MutableStateFlow<SyncLink?>(null)
    /** The link of the QR code while pairing is open and the server listens */
    val pairingLink: StateFlow<SyncLink?> = _pairingLink.asStateFlow()

    /** The port the server listens on, 0 when it does not */
    val port: Int get() = serverSocket?.localPort ?: 0

    fun start() {
        if (acceptJob != null) return
        acceptJob = scope.launch {
            val socket = withContext(Dispatchers.IO) { bind() } ?: return@launch
            serverSocket = socket
            settings.serverPort = socket.localPort
            discovery?.advertise(settings.deviceId, socket.localPort)
            Log.d(TAG, "listening on ${socket.localPort}")
            updatePairingLink()
            withContext(Dispatchers.IO) {
                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    sessionSockets += client
                    //destroy() may have closed the sessions before this one was added
                    if (destroyed) closeSessions()
                    scope.launch(Dispatchers.IO) { handle(client) }
                }
            }
        }
    }

    fun stop() {
        if (acceptJob != null) discovery?.stopAdvertising()
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (e: IOException) {
        }
        serverSocket = null
        updatePairingLink()
    }

    /** Ends the sessions too */
    fun destroy() {
        destroyed = true
        stop()
        scope.cancel()
        closeSessions()
    }

    private fun closeSessions() {
        //the iterator of a concurrent set is safe while sessions remove their sockets (a copy made with
        //toList() is not: it reads the size first, then fails when a socket is gone before it is taken)
        for (socket in sessionSockets) {
            sessionSockets -= socket
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }
    }

    /** A new secret: a phone that has the QR code can pair until [stopPairing] */
    fun startPairing() {
        pairingSecret = SyncCrypto.randomBytes(SyncCrypto.KEY_SIZE)
        updatePairingLink()
    }

    fun stopPairing() {
        pairingSecret = null
        updatePairingLink()
    }

    private fun updatePairingLink() {
        val secret = pairingSecret
        val port = port
        _pairingLink.value = if (secret == null || port == 0) null else
            SyncLink(settings.localAddresses().ifEmpty { listOf("0.0.0.0") }, port, secret, settings.deviceId, settings.deviceName)
        if (BuildConfig.DEBUG) _pairingLink.value?.let { Log.d(TAG, "pairing link: ${it.toUrl()}") }
    }

    private fun bind(): ServerSocket? {
        val preferred = settings.serverPort
        for (port in listOf(preferred, 0).distinct()) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, port))
                }
            } catch (e: IOException) {
                Log.w(TAG, "can not listen on $port", e)
            }
        }
        return null
    }

    private suspend fun handle(socket: Socket) {
        val address = socket.inetAddress?.hostAddress
        try {
            socket.use {
                socket.soTimeout = HANDSHAKE_TIMEOUT
                socket.tcpNoDelay = true
                session(socket)
            }
        } catch (e: SyncException) {
            Log.w(TAG, "session of $address: ${e.code}")
        } catch (e: EOFException) {
            //the phone closed the session
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "session of $address timed out")
        } catch (e: IOException) {
            //after destroy() it is the closed socket
            if (!destroyed) Log.w(TAG, "session of $address", e)
        } catch (e: Exception) {
            //any device of the network can open a session: nothing it sends may stop the app
            Log.e(TAG, "session of $address", e)
        } finally {
            sessionSockets -= socket
        }
    }

    private suspend fun session(socket: Socket) {
        val repo = data().pairedDevices
        //the secret is read once: pairing with it ends it
        val secret = pairingSecret
        val accepted = SyncChannel.accept(socket.getInputStream(), socket.getOutputStream()) { pairingId ->
            if (pairingId == null) secret else runBlocking {
                repo.findByPairingId(pairingId)?.takeIf { it.kind == PairedDevice.KIND_PHONE }?.let(repo::keyOf)
            }
        }
        val channel = accepted.channel
        val phone = DeviceInfo.fromJson(channel.receive())
        var device: PairedDevice
        val reply: DeviceInfo
        if (accepted.pairingId == null) {
            if (pairingSecret !== secret) throw SyncException(SyncChannel.PAIRING_CLOSED)
            val pairing = NewPairing(SyncCrypto.randomId(), SyncCrypto.randomBytes(SyncCrypto.KEY_SIZE))
            device = repo.add(pairing.id, phone.id, PairedDevice.KIND_PHONE, phone.name, pairing.key)
            reply = DeviceInfo(settings.deviceId, settings.deviceName, appVersion, SyncRequests.TV_CAPABILITIES, pairing)
        } else {
            device = repo.findByPairingId(accepted.pairingId) ?: throw SyncException(SyncChannel.UNKNOWN_PAIRING)
            device = repo.markSeen(device, phone.name)
            reply = DeviceInfo(settings.deviceId, settings.deviceName, appVersion, SyncRequests.TV_CAPABILITIES)
        }
        channel.send(reply.toJson())
        if (reply.pairing != null) {
            withContext(mainDispatcher) {
                //the code is used: a new one for the next phone
                if (pairingSecret === secret) startPairing()
                host.onPaired(device)
            }
        }

        socket.soTimeout = IDLE_TIMEOUT
        while (true) {
            val request = channel.receive()
            val id = request.optLong("id")
            val type = request.optString("type")
            if (type == SyncRequests.SEND_FILE) {
                receiveFile(socket, channel, device, id, request)
                continue
            }
            val response = try {
                handleRequest(device, type, request)
            } catch (e: SyncException) {
                SyncRequests.error(e.code)
            } catch (e: Exception) {
                Log.e(TAG, "request $type failed", e)
                SyncRequests.error(SyncRequests.FAILED)
            }
            channel.send(response.put("re", id))
            if (type == SyncRequests.FORGET && response.optString("type") == SyncRequests.RESULT) return
        }
    }

    private suspend fun handleRequest(device: PairedDevice, type: String, request: JSONObject): JSONObject = when (type) {
        SyncRequests.OPEN_TABS -> {
            val tabs = SyncRequests.tabsOf(request)
            if (tabs.isEmpty()) throw SyncException(SyncRequests.NOTHING_TO_OPEN)
            withContext(mainDispatcher) { host.openTabs(device, tabs) }
            SyncRequests.result().put("opened", tabs.size)
        }
        SyncRequests.GET_BOOKMARKS -> SyncRequests.result().put("tree", SyncBookmark.toJson(bookmarks.tree()))
        SyncRequests.PREVIEW_BOOKMARKS -> {
            val tree = SyncBookmark.fromJson(request.optJSONObject("tree") ?: throw SyncException(SyncChannel.BAD_MESSAGE))
            changesJson(bookmarks.preview(tree))
        }
        SyncRequests.REPLACE_BOOKMARKS -> {
            val tree = SyncBookmark.fromJson(request.optJSONObject("tree") ?: throw SyncException(SyncChannel.BAD_MESSAGE))
            //what the phone showed to the user must still be what is done
            val expected = request.optJSONObject("expect")?.let {
                BookmarksReplacer.Changes(it.optInt("added"), it.optInt("updated"), it.optInt("removed"), emptyList())
            }
            val changes = bookmarks.replace(tree, expected)
            withContext(mainDispatcher) { host.onBookmarksReplaced(device, changes) }
            changesJson(changes)
        }
        SyncRequests.FORGET -> {
            data().pairedDevices.delete(device)
            withContext(mainDispatcher) { host.onForgotten(device) }
            SyncRequests.result()
        }
        else -> SyncRequests.error(SyncRequests.UNSUPPORTED)
    }

    /**
     * A file of the phone (see [SyncRequests.SEND_FILE]). A file that can not be received whole ends the
     * session: the phone may still be sending its bytes.
     */
    private suspend fun receiveFile(socket: Socket, channel: SyncChannel, device: PairedDevice, id: Long, request: JSONObject) {
        val name = SyncRequests.safeFileName(request.optString("name"))
        val size = request.optLong("size", -1L).coerceAtLeast(-1L)
        val mimeType = SyncRequests.safeMimeType(request.optString("mime"))
        val file = try {
            withContext(mainDispatcher) { host.receiveFile(device, name, mimeType, size) }
        } catch (e: SyncException) {
            channel.send(SyncRequests.error(e.code).put("re", id))
            return
        } catch (e: Exception) {
            Log.e(TAG, "can not receive $name", e)
            channel.send(SyncRequests.error(SyncRequests.FAILED).put("re", id))
            return
        }
        channel.send(SyncRequests.result().put("re", id))
        try {
            while (true) {
                val chunk = channel.receiveBytes()
                if (chunk.isEmpty()) break
                try {
                    file.output.write(chunk)
                } catch (e: IOException) {
                    throw WriteFailure(e)
                }
            }
            try {
                file.output.close()
            } catch (e: IOException) {
                throw WriteFailure(e)
            }
        } catch (e: WriteFailure) {
            //the download was cancelled on the TV, or its file can not be written: the phone is told why
            file.fail(e.toString())
            val code = try {
                withTimeoutOrNull(FAILURE_TIMEOUT) { file.awaitSaved() }
                SyncRequests.FAILED
            } catch (failure: SyncException) {
                failure.code
            }
            channel.send(SyncRequests.error(code).put("re", id))
            drain(socket, channel)
            throw SyncException(code)
        } catch (e: Throwable) {
            //the phone is gone, or sent something else
            file.fail(e.toString())
            throw e
        }
        val response = try {
            val saved = withTimeoutOrNull(SAVE_TIMEOUT) { file.awaitSaved() }
            if (saved == null) SyncRequests.error(SyncRequests.FAILED) else SyncRequests.result().put("name", saved)
        } catch (e: SyncException) {
            SyncRequests.error(e.code)
        }
        channel.send(response.put("re", id))
    }

    private class WriteFailure(cause: IOException) : IOException(cause)

    /**
     * Reads what the phone still sends of a file that failed, for a while: closing a connection that has
     * unread bytes resets it, and the phone would lose the error before it reads it. The phone stops when it
     * sees the error, or sends the end of the file.
     */
    private fun drain(socket: Socket, channel: SyncChannel) {
        try {
            socket.soTimeout = DRAIN_TIMEOUT
            val end = System.currentTimeMillis() + DRAIN_TIMEOUT
            while (System.currentTimeMillis() < end) {
                if (channel.receiveBytes().isEmpty()) break
            }
        } catch (e: IOException) {
            //the phone closed the connection
        }
    }

    private fun changesJson(changes: BookmarksReplacer.Changes): JSONObject = SyncRequests.result()
        .put("added", changes.added)
        .put("updated", changes.updated)
        .put("removed", changes.removed)
        .put("removedTitles", JSONArray(changes.removedTitles))
}
