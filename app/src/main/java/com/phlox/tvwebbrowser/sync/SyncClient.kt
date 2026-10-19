package com.phlox.tvwebbrowser.sync

import android.util.Log
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.data.PairedDevicesRepository
import com.phlox.tvwebbrowser.model.PairedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone side: pairs with a TV by the link of its QR code ([pair]) and opens sessions with paired TVs
 * ([session]). All of it runs on a background thread.
 */
class SyncClient(
    private val settings: SyncSettings,
    private val repo: () -> PairedDevicesRepository,
    /** Where a paired TV may be now, besides where it was last (found in the network) */
    private val discovery: suspend (tvId: String) -> List<InetSocketAddress> = { emptyList() },
    private val appVersion: Int = BuildConfig.VERSION_CODE
) {
    companion object {
        private const val TAG = "SyncClient"
        private const val CONNECT_TIMEOUT = 3_000
        /** The TV may take a while to change many bookmarks */
        private const val READ_TIMEOUT = 60_000
        private const val HANDSHAKE_TIMEOUT = 5_000

        /** The TV is not where it was and was not found: not on, TV Bro not open, or in another network */
        const val UNREACHABLE = "unreachable"
    }

    /** A connection to a paired TV: requests and their answers, one at a time */
    class Session internal constructor(
        private val socket: Socket,
        private val channel: SyncChannel,
        val tv: PairedDevice,
        val info: DeviceInfo
    ) {
        private var lastId = 0L

        /** @throws SyncException with the code of the error the TV answered */
        fun request(request: JSONObject): JSONObject {
            val id = ++lastId
            channel.send(request.put("id", id))
            while (true) {
                val response = channel.receive()
                if (response.optLong("re") != id) continue
                if (response.optString("type") == SyncRequests.ERROR) {
                    throw SyncException(response.optString("code", SyncRequests.FAILED))
                }
                return response
            }
        }

        fun supports(type: String) = type in info.capabilities

        /** @return the number of the tabs the TV opened */
        fun openTabs(tabs: List<SyncTab>): Int = request(SyncRequests.openTabs(tabs)).optInt("opened")

        /**
         * All the bookmarks of the TV
         * @throws SyncException [SyncRequests.UNSUPPORTED] from a TV of an older version
         */
        fun getBookmarks(): SyncBookmark.Folder {
            if (!supports(SyncRequests.GET_BOOKMARKS)) throw SyncException(SyncRequests.UNSUPPORTED)
            val tree = request(JSONObject().put("type", SyncRequests.GET_BOOKMARKS)).optJSONObject("tree")
                ?: throw SyncException(SyncChannel.BAD_MESSAGE)
            return SyncBookmark.fromJson(tree)
        }

        fun previewBookmarks(tree: SyncBookmark.Folder): BookmarksReplacer.Changes =
            changesOf(request(JSONObject().put("type", SyncRequests.PREVIEW_BOOKMARKS).put("tree", SyncBookmark.toJson(tree))))

        /** @param expected the changes of the preview: if the TV would now change anything else, it does nothing ([SyncRequests.CHANGED]) */
        fun replaceBookmarks(tree: SyncBookmark.Folder, expected: BookmarksReplacer.Changes?): BookmarksReplacer.Changes {
            val request = JSONObject().put("type", SyncRequests.REPLACE_BOOKMARKS).put("tree", SyncBookmark.toJson(tree))
            if (expected != null) {
                request.put("expect", JSONObject().put("added", expected.added).put("updated", expected.updated)
                    .put("removed", expected.removed))
            }
            return changesOf(request(request))
        }

        /** The TV forgets this phone */
        fun forget() {
            request(JSONObject().put("type", SyncRequests.FORGET))
        }

        internal fun close() {
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }

        private fun changesOf(json: JSONObject): BookmarksReplacer.Changes {
            val titles = json.optJSONArray("removedTitles")
            return BookmarksReplacer.Changes(json.optInt("added"), json.optInt("updated"), json.optInt("removed"),
                (0 until (titles?.length() ?: 0)).map { titles!!.optString(it) })
        }
    }

    /**
     * Pairs with the TV of the [link]: the TV gives the key of the pairing, which is saved.
     * @throws SyncException [UNREACHABLE] if no address of the link answers, or the code of the TV
     */
    suspend fun pair(link: SyncLink): PairedDevice = withContext(Dispatchers.IO) {
        val addresses = nearestFirst(link.addresses).map { InetSocketAddress(it, link.port) }
        val (socket, address) = connect(addresses)
        socket.use {
            val channel = SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(), link.secret, pairingId = null)
            channel.send(ownInfo().toJson())
            val info = DeviceInfo.fromJson(channel.receive())
            val pairing = info.pairing ?: throw SyncException(SyncChannel.BAD_MESSAGE)
            repo().add(pairing.id, info.id, PairedDevice.KIND_TV, info.name, pairing.key,
                host = address.address?.hostAddress ?: address.hostString, port = address.port)
        }
    }

    /**
     * Connects to the paired [tv] (where it was last, else where it is found) and runs the [block] in a
     * session, on a background thread.
     * @throws SyncException [UNREACHABLE], [SyncChannel.UNKNOWN_PAIRING] if the TV forgot this phone,
     *   or the code of a request
     */
    suspend fun <T> session(tv: PairedDevice, block: (Session) -> T): T = withContext(Dispatchers.IO) {
        val key = repo().keyOf(tv) ?: throw SyncException(SyncChannel.UNKNOWN_PAIRING, "the key can not be read")
        val known = listOfNotNull(tv.host?.let { InetSocketAddress(it, tv.port) }.takeIf { tv.port != 0 })
        val session = try {
            open(tv, key, known)
        } catch (e: IOException) {
            //another device may have the address now, or the TV listens on another port
            if (e is SyncException && e.code == SyncChannel.UNKNOWN_PAIRING) throw e
            val found = discovery(tv.peerId).filter { it !in known }
            if (found.isEmpty()) throw e
            open(tv, key, found)
        }
        try {
            block(session)
        } finally {
            session.close()
        }
    }

    /**
     * The addresses of the TV in the network of this phone (the same first three numbers) first: a TV may
     * have addresses in networks the phone can not reach, and each of them takes a timeout
     */
    private fun nearestFirst(addresses: List<String>): List<String> {
        val own = settings.localAddresses().map { it.substringBeforeLast('.') }.toSet()
        return addresses.sortedBy { if (it.substringBeforeLast('.') in own) 0 else 1 }
    }

    /** Connects to the first of the [addresses] that answers and makes a session with the [tv] */
    private fun open(tv: PairedDevice, key: ByteArray, addresses: List<InetSocketAddress>): Session {
        val (socket, address) = connect(addresses)
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT
            val channel = SyncChannel.connect(socket.getInputStream(), socket.getOutputStream(), key, tv.pairingId)
            channel.send(ownInfo().toJson())
            val info = DeviceInfo.fromJson(channel.receive())
            socket.soTimeout = READ_TIMEOUT
            val seen = runBlocking { repo().markSeen(tv, info.name, address.address?.hostAddress ?: address.hostString, address.port) }
            return Session(socket, channel, seen, info)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    private fun ownInfo() = DeviceInfo(settings.deviceId, settings.deviceName, appVersion, emptySet())

    /** The first of the [addresses] that accepts a connection */
    private fun connect(addresses: List<InetSocketAddress>): Pair<Socket, InetSocketAddress> {
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(address, CONNECT_TIMEOUT)
                socket.soTimeout = READ_TIMEOUT
                socket.tcpNoDelay = true
                return socket to address
            } catch (e: IOException) {
                Log.d(TAG, "no connection to $address: $e")
                socket.close()
            }
        }
        throw SyncException(UNREACHABLE)
    }
}
