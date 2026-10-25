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
    /**
     * A file for the downloads of the TV: `{"type":"files.send","name":…,"mime":…,"size":…}` (the size is -1
     * when the phone does not know it). The TV answers a result when it is ready (or an error), then the phone
     * sends the bytes in frames of bytes ([SyncChannel.sendBytes], at most [FILE_CHUNK] each) and an empty
     * frame at the end; the TV answers again when the file is saved, with its `name` (the TV may rename it).
     * If the TV can not go on meanwhile, it sends an error and closes the connection.
     */
    const val SEND_FILE = "files.send"
    const val RESULT = "result"
    const val ERROR = "error"

    /** What a TV of this version handles */
    val TV_CAPABILITIES = setOf(OPEN_TABS, GET_BOOKMARKS, PREVIEW_BOOKMARKS, REPLACE_BOOKMARKS, FORGET, SEND_FILE)

    const val MAX_URL_LENGTH = 8192
    const val MAX_TITLE_LENGTH = 1024
    const val MAX_NAME_LENGTH = 100
    const val MAX_TABS = 500
    /** In bytes of UTF-8: file systems take 255 */
    const val MAX_FILE_NAME_BYTES = 200
    const val FILE_CHUNK = 128 * 1024
    /** The scheme of the url of a download that came from a phone (it is not downloaded again) */
    private const val PHONE_FILE_SCHEME = "tvbro-phone:"

    /** Error codes of requests (besides those of [SyncChannel]) */
    const val UNSUPPORTED = "unsupported"
    const val NOTHING_TO_OPEN = "nothing_to_open"
    const val FAILED = "failed"
    /** The bookmarks of the TV changed after the preview */
    const val CHANGED = "changed"
    /** The TV can not save files until the user allows it there (it asked) */
    const val NEED_PERMISSION = "need_permission"
    /** The TV could not write the file */
    const val STORAGE = "storage"
    /** The user cancelled the file on the TV */
    const val CANCELLED = "cancelled"

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

    fun sendFile(name: String, mimeType: String?, size: Long): JSONObject = JSONObject()
        .put("type", SEND_FILE)
        .put("name", name)
        .put("mime", mimeType ?: "")
        .put("size", size)

    /**
     * The name of a file a phone sent, safe to be a file name of the TV: no folders (`../`), no control
     * characters and characters FAT drives do not take, not hidden, not too long (its extension kept)
     */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filter { it >= ' ' && it != '\u007f' &&it !in "<>:\"|?*" }.trim().trimStart('.').trim()
        if (clean.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES) return clean.ifEmpty { "file" }
        val ext = clean.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 16 }
        val suffix = if (ext != null) ".$ext" else ""
        val stem = if (ext != null) clean.substring(0, clean.length - suffix.length) else clean
        return (takeUtf8Bytes(stem, MAX_FILE_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size).trim() + suffix)
            .ifEmpty { "file" }
    }

    /** The beginning of the [text] that has at most [max] bytes of UTF-8, whole code points only */
    private fun takeUtf8Bytes(text: String, max: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > max) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    /** A type as `image/jpeg`, else null */
    fun safeMimeType(mimeType: String?): String? =
        mimeType?.trim()?.lowercase()?.takeIf { it.length <= 127 && MIME_TYPE.matches(it) }

    private val MIME_TYPE = Regex("[a-z0-9][a-z0-9.+_-]*/[a-z0-9][a-z0-9.+_-]*")

    /** The url of a download a phone of that [phoneName] sent */
    fun phoneFileUrl(phoneName: String): String = PHONE_FILE_SCHEME + URLEncoder.encode(phoneName, "UTF-8")

    /** The name of the phone that sent the download of the [url], null if it was not a phone */
    fun phoneOfFileUrl(url: String): String? {
        if (!url.startsWith(PHONE_FILE_SCHEME)) return null
        return try {
            URLDecoder.decode(url.substring(PHONE_FILE_SCHEME.length), "UTF-8").takeIf { it.isNotBlank() }
        } catch (e: IllegalArgumentException) {
            null
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
