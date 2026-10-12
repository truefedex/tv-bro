package com.phlox.tvwebbrowser.player

/**
 * Manifest-level metadata for a detected stream. Purely informational: it is parsed
 * from HLS/DASH manifests that are already fetched, never from the media payload.
 * Every field is optional because most manifests omit most of it.
 */
data class MediaMetadata(
    val width: Int? = null,
    val height: Int? = null,
    val languages: List<String> = emptyList(),
    val durationMs: Long? = null,
    val bandwidth: Long? = null
) {
    val resolution: String?
        get() = if (width != null && height != null) "${width}×$height" else null

    fun isEmpty(): Boolean =
        width == null && height == null && languages.isEmpty() && durationMs == null && bandwidth == null
}
