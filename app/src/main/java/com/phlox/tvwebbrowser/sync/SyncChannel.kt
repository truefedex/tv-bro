package com.phlox.tvwebbrowser.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

/**
 * A failure of the sync: [code] is one of the codes of [SyncChannel] and of the requests
 * (sent to the other side as `{"type":"error","code":...}`).
 */
class SyncException(val code: String, message: String? = null) : IOException(message ?: code)

/**
 * The encrypted channel between a phone (the client) and a TV (the server) over a stream.
 *
 * Messages are JSON objects in frames: the length (4 bytes, big endian) and the bytes. The handshake is in
 * the clear: the client sends `{"type":"hello","protocol":1,"nonce":…,"pairing":…}` (no pairing when it
 * pairs with the secret of a QR code), the server answers with its own nonce. Both sides derive the keys
 * of the two directions from the shared key (the pairing key or the secret of the QR code) with
 * HKDF over both nonces, so the keys of a session are new even when the shared key is not. Every later
 * frame is sealed with AES-GCM and the number of the frame: a frame of another key, another session or
 * out of its place fails, which also tells a side that the other one does not have the key.
 */
class SyncChannel private constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray
) {
    private var sent = 0L
    private var received = 0L

    fun send(message: JSONObject) {
        val sealed = SyncCrypto.seal(sendKey, sent++, message.toString().toByteArray(Charsets.UTF_8))
        writeFrame(output, sealed)
    }

    /** @throws SyncException [BAD_KEY] if the frame was not sealed with the key of the other side */
    fun receive(): JSONObject {
        val sealed = readFrame(input)
        val plain = try {
            SyncCrypto.open(receiveKey, received++, sealed)
        } catch (e: GeneralSecurityException) {
            throw SyncException(BAD_KEY)
        }
        return parse(plain)
    }

    /** What the server got from a client that has a key */
    class Accepted(val channel: SyncChannel, val pairingId: String?)

    companion object {
        const val PROTOCOL = 1
        /** Bookmarks of a big tree fit in it */
        const val MAX_FRAME = 8 * 1024 * 1024
        /** The handshake comes before anything shows that the other side has a key */
        private const val MAX_HANDSHAKE_FRAME = 4 * 1024

        const val BAD_KEY = "bad_key"
        const val UNKNOWN_PAIRING = "unknown_pairing"
        const val PAIRING_CLOSED = "pairing_closed"
        const val BAD_PROTOCOL = "bad_protocol"
        const val BAD_MESSAGE = "bad_message"

        /**
         * The client side of the handshake.
         * @param pairingId the pairing whose [key] it is, null for the secret of a QR code
         */
        fun connect(input: InputStream, output: OutputStream, key: ByteArray, pairingId: String?): SyncChannel {
            val dataIn = DataInputStream(input)
            val dataOut = DataOutputStream(output)
            val nonce = SyncCrypto.randomBytes(SyncCrypto.NONCE_SIZE)
            val hello = JSONObject()
                .put("type", "hello")
                .put("protocol", PROTOCOL)
                .put("nonce", SyncCrypto.encode(nonce))
            if (pairingId != null) hello.put("pairing", pairingId)
            writeFrame(dataOut, hello.toString().toByteArray(Charsets.UTF_8))

            val answer = parse(readFrame(dataIn, MAX_HANDSHAKE_FRAME))
            if (answer.optString("type") == "error") throw SyncException(answer.optString("code", BAD_MESSAGE))
            val serverNonce = answer.optString("nonce").let(SyncCrypto::decode)
            if (answer.optString("type") != "hello" || serverNonce == null || serverNonce.size != SyncCrypto.NONCE_SIZE) {
                throw SyncException(BAD_MESSAGE)
            }
            val keys = Keys(key, pairingId, nonce, serverNonce)
            return SyncChannel(dataIn, dataOut, sendKey = keys.toServer, receiveKey = keys.toClient)
        }

        /**
         * The server side of the handshake.
         * @param keyOf the key of a pairing, or of a QR code when the client gives no pairing (null if there
         *   is no such pairing, or no QR code is shown: the client is told [UNKNOWN_PAIRING] or [PAIRING_CLOSED])
         */
        fun accept(input: InputStream, output: OutputStream, keyOf: (pairingId: String?) -> ByteArray?): Accepted {
            val dataIn = DataInputStream(input)
            val dataOut = DataOutputStream(output)
            val hello = parse(readFrame(dataIn, MAX_HANDSHAKE_FRAME))
            val clientNonce = hello.optString("nonce").let(SyncCrypto::decode)
            if (hello.optString("type") != "hello" || clientNonce == null || clientNonce.size != SyncCrypto.NONCE_SIZE) {
                throw SyncException(BAD_MESSAGE)
            }
            if (hello.optInt("protocol") != PROTOCOL) {
                refuse(dataOut, BAD_PROTOCOL)
            }
            val pairingId = hello.optString("pairing").takeIf { it.isNotEmpty() }
            val key = keyOf(pairingId) ?: refuse(dataOut, if (pairingId == null) PAIRING_CLOSED else UNKNOWN_PAIRING)

            val nonce = SyncCrypto.randomBytes(SyncCrypto.NONCE_SIZE)
            val answer = JSONObject()
                .put("type", "hello")
                .put("protocol", PROTOCOL)
                .put("nonce", SyncCrypto.encode(nonce))
            writeFrame(dataOut, answer.toString().toByteArray(Charsets.UTF_8))
            val keys = Keys(key, pairingId, clientNonce, nonce)
            return Accepted(SyncChannel(dataIn, dataOut, sendKey = keys.toClient, receiveKey = keys.toServer), pairingId)
        }

        private fun refuse(output: DataOutputStream, code: String): Nothing {
            writeFrame(output, JSONObject().put("type", "error").put("code", code).toString().toByteArray(Charsets.UTF_8))
            throw SyncException(code)
        }

        private class Keys(key: ByteArray, pairingId: String?, clientNonce: ByteArray, serverNonce: ByteArray) {
            private val salt = clientNonce + serverNonce
            private val context = "tvbro-sync $PROTOCOL ${pairingId ?: "qr"}"
            val toServer = SyncCrypto.hkdf(key, salt, "$context c2s")
            val toClient = SyncCrypto.hkdf(key, salt, "$context s2c")
        }

        private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        private fun readFrame(input: DataInputStream, maxSize: Int = MAX_FRAME): ByteArray {
            val size = input.readInt()
            if (size < 0 || size > maxSize) throw SyncException(BAD_MESSAGE, "frame of $size bytes")
            return ByteArray(size).also { input.readFully(it) }
        }

        private fun parse(bytes: ByteArray): JSONObject = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw SyncException(BAD_MESSAGE)
        }
    }
}
