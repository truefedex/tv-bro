package com.phlox.tvwebbrowser.sync

import android.util.Log
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.model.PairedDevice
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The server of a TV: paired phones connect to it to open tabs and to send bookmarks, a new phone pairs
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
    private val discovery: SyncDiscovery? = null
) {
    companion object {
        private const val TAG = "SyncServer"
        private const val HANDSHAKE_TIMEOUT = 15_000
        /** A phone keeps its session while its sync page is open */
        private const val IDLE_TIMEOUT = 120_000
    }

    interface Host {
        fun openTabs(from: PairedDevice, tabs: List<SyncTab>)
        fun onBookmarksReplaced(from: PairedDevice, changes: BookmarksReplacer.Changes)
        fun onPaired(device: PairedDevice)
        /** The phone asked to be forgotten (its pairing is deleted) */
        fun onForgotten(device: PairedDevice)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

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
        stop()
        scope.cancel()
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
            Log.w(TAG, "session of $address", e)
        } catch (e: Exception) {
            //any device of the network can open a session: nothing it sends may stop the app
            Log.e(TAG, "session of $address", e)
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
            withContext(Dispatchers.Main) {
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
            withContext(Dispatchers.Main) { host.openTabs(device, tabs) }
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
            withContext(Dispatchers.Main) { host.onBookmarksReplaced(device, changes) }
            changesJson(changes)
        }
        SyncRequests.FORGET -> {
            data().pairedDevices.delete(device)
            withContext(Dispatchers.Main) { host.onForgotten(device) }
            SyncRequests.result()
        }
        else -> SyncRequests.error(SyncRequests.UNSUPPORTED)
    }

    private fun changesJson(changes: BookmarksReplacer.Changes): JSONObject = SyncRequests.result()
        .put("added", changes.added)
        .put("updated", changes.updated)
        .put("removed", changes.removed)
        .put("removedTitles", JSONArray(changes.removedTitles))
}
