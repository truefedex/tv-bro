package com.phlox.tvwebbrowser.utils

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/** What the media codecs of the device decode in hardware */
object HardwareCodecs {
    private const val TAG = "HardwareCodecs"
    const val AV1 = "video/av01"

    /** Whether a decoder of the device decodes [mime] in hardware */
    fun hasHardwareDecoder(mime: String): Boolean {
        val codecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            Log.w(TAG, "No codec list", e)
            return false
        }
        return codecs.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && isHardware(info)
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        isHardware(info.name, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else null)

    /**
     * Android 10 tells whether a codec is accelerated ([hardwareAccelerated]); before, the codecs of
     * Android itself (and the FFmpeg ones of some vendors) are known by their names.
     */
    internal fun isHardware(name: String, hardwareAccelerated: Boolean?): Boolean {
        if (hardwareAccelerated != null) return hardwareAccelerated
        val n = name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") ||
                n.contains(".sw.") || n.contains("ffmpeg"))
    }
}
