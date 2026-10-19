package com.phlox.tvwebbrowser.sync

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a side tells about itself right after the handshake (the first sealed message of each side,
 * `{"type":"device",…}`): its id (of the installation), its name, the version of the app and the
 * requests it handles. A TV paired with the secret of a QR code also gives the new [pairing].
 */
class DeviceInfo(
    val id: String,
    val name: String,
    val appVersion: Int,
    val capabilities: Set<String>,
    val pairing: NewPairing? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("type", TYPE)
            .put("id", id)
            .put("name", name)
            .put("app", appVersion)
            .put("caps", JSONArray(capabilities.toList()))
        if (pairing != null) {
            json.put("pairing", JSONObject().put("id", pairing.id).put("key", SyncCrypto.encode(pairing.key)))
        }
        return json
    }

    companion object {
        const val TYPE = "device"

        /** @throws SyncException [SyncChannel.BAD_MESSAGE] */
        fun fromJson(json: JSONObject): DeviceInfo {
            val id = json.optString("id")
            if (json.optString("type") != TYPE || id.isEmpty()) throw SyncException(SyncChannel.BAD_MESSAGE)
            val caps = json.optJSONArray("caps")
            val pairing = json.optJSONObject("pairing")?.let { p ->
                val key = SyncCrypto.decode(p.optString("key"))
                val pairingId = p.optString("id")
                if (key == null || key.size != SyncCrypto.KEY_SIZE || pairingId.isEmpty()) {
                    throw SyncException(SyncChannel.BAD_MESSAGE)
                }
                NewPairing(pairingId, key)
            }
            return DeviceInfo(
                id = id,
                name = json.optString("name").take(SyncRequests.MAX_NAME_LENGTH).ifBlank { id },
                appVersion = json.optInt("app"),
                capabilities = (0 until (caps?.length() ?: 0)).map { caps!!.optString(it) }.toSet(),
                pairing = pairing
            )
        }
    }
}

class NewPairing(val id: String, val key: ByteArray)

/** A page sent to the TV */
data class SyncTab(val url: String, val title: String)

/**
 * The requests of the phone and the answers of the TV: `{"id":1,"type":"tabs.open",…}` is answered with
 * `{"re":1,"type":"result",…}` or `{"re":1,"type":"error","code":…}`.
 */
object SyncRequests {
    const val OPEN_TABS = "tabs.open"
    /** All the bookmarks of the TV, for the phone to take some */
    const val GET_BOOKMARKS = "bookmarks.get"
    const val PREVIEW_BOOKMARKS = "bookmarks.preview"
    const val REPLACE_BOOKMARKS = "bookmarks.replace"
    const val FORGET = "device.forget"
    const val RESULT = "result"
    const val ERROR = "error"

    /** What a TV of this version handles */
    val TV_CAPABILITIES = setOf(OPEN_TABS, GET_BOOKMARKS, PREVIEW_BOOKMARKS, REPLACE_BOOKMARKS, FORGET)

    const val MAX_URL_LENGTH = 8192
    const val MAX_TITLE_LENGTH = 1024
    const val MAX_NAME_LENGTH = 100
    const val MAX_TABS = 500

    /** Error codes of requests (besides those of [SyncChannel]) */
    const val UNSUPPORTED = "unsupported"
    const val NOTHING_TO_OPEN = "nothing_to_open"
    const val FAILED = "failed"
    /** The bookmarks of the TV changed after the preview */
    const val CHANGED = "changed"

    /** Pages other than http and https (javascript:, file:, intent:…) are never sent or opened */
    fun isAllowedUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        val scheme = url.substringBefore(':', "").lowercase()
        return (scheme == "http" || scheme == "https") && url.length > scheme.length + 3
    }

    fun openTabs(tabs: List<SyncTab>): JSONObject = JSONObject()
        .put("type", OPEN_TABS)
        .put("tabs", JSONArray(tabs.map { JSONObject().put("url", it.url).put("title", it.title) }))

    /** The allowed tabs of an [openTabs] request */
    fun tabsOf(request: JSONObject): List<SyncTab> {
        val array = request.optJSONArray("tabs") ?: return emptyList()
        return (0 until minOf(array.length(), MAX_TABS)).mapNotNull { i ->
            val tab = array.optJSONObject(i) ?: return@mapNotNull null
            val url = tab.optString("url")
            if (!isAllowedUrl(url)) null else SyncTab(url, tab.optString("title").take(MAX_TITLE_LENGTH))
        }
    }

    fun result(): JSONObject = JSONObject().put("type", RESULT)

    fun error(code: String): JSONObject = JSONObject().put("type", ERROR).put("code", code)
}

/**
 * The link of the QR code a TV shows: `https://tvbro.phlox.dev/sync#v=1&a=…&p=…&k=…&i=…&n=…`. The fragment
 * holds the addresses and the port of the TV, the secret of the pairing, the id and the name of the TV;
 * it never goes to the site (browsers do not send the fragment), so the secret stays between the devices.
 * Where the app does not open the link itself, the page of the site at that address opens it as
 * `tvbro://sync#…` (the same fragment).
 */
data class SyncLink(
    val addresses: List<String>,
    val port: Int,
    val secret: ByteArray,
    val tvId: String,
    val tvName: String
) {
    fun toUrl(): String {
        val params = listOf(
            "v" to SyncChannel.PROTOCOL.toString(),
            "a" to addresses.joinToString(","),
            "p" to port.toString(),
            "k" to SyncCrypto.encode(secret),
            "i" to tvId,
            "n" to tvName
        )
        return URL_PREFIX + "#" + params.joinToString("&") { (name, value) -> name + "=" + encode(value) }
    }

    override fun equals(other: Any?): Boolean = other is SyncLink && addresses == other.addresses &&
        port == other.port && secret.contentEquals(other.secret) && tvId == other.tvId && tvName == other.tvName

    override fun hashCode(): Int = toUrl().hashCode()

    companion object {
        const val URL_PREFIX = "https://tvbro.phlox.dev/sync"
        /** The page of the site gives the link to the app with this scheme */
        const val APP_URL_PREFIX = "tvbro://sync"

        /** @return null if the url is not a link of a pairing (or of another version of the protocol) */
        fun parse(url: String): SyncLink? {
            val fragment = url.substringAfter('#', "")
            if (!isSyncUrl(url) || fragment.isEmpty()) return null
            val params = fragment.split('&').mapNotNull {
                val name = it.substringBefore('=', "")
                if (name.isEmpty()) null else name to decode(it.substringAfter('='))
            }.toMap()
            if (params["v"] != SyncChannel.PROTOCOL.toString()) return null
            val addresses = params["a"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val secret = params["k"]?.let(SyncCrypto::decode)?.takeIf { it.size == SyncCrypto.KEY_SIZE } ?: return null
            val tvId = params["i"]?.takeIf { it.isNotEmpty() } ?: return null
            if (addresses.isEmpty()) return null
            return SyncLink(addresses, port, secret, tvId, params["n"].orEmpty().take(SyncRequests.MAX_NAME_LENGTH))
        }

        /** Whether the url is a link of a pairing, maybe of another version */
        fun isSyncUrl(url: String): Boolean {
            val base = url.substringBefore('#').substringBefore('?').trimEnd('/')
            return base.equals(URL_PREFIX, ignoreCase = true) || base.equals(APP_URL_PREFIX, ignoreCase = true)
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        private fun decode(value: String) = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            ""
        }
    }
}
