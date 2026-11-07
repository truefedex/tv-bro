package com.phlox.tvwebbrowser.webengine

import android.util.Log
import com.phlox.tvwebbrowser.net.AppProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/** What an HLS playlist (.m3u8) tells about the videos of a page */
sealed class HlsPlaylist {
    /** A master playlist: the absolute addresses of the playlists it lists (the qualities, audio, subtitles) */
    data class Master(val playlists: Set<String>) : HlsPlaylist()

    /** A media playlist: its segments last [duration] seconds; [ended]: it is all of the video (no live stream) */
    data class Media(val duration: Double, val ended: Boolean) : HlsPlaylist()

    /**
     * Not HLS: the manifest of a DASH stream (MPD), which an XML file the page fetched may be
     * ([PageVideo.Kind.XML])
     */
    data object Dash : HlsPlaylist()

    companion object {
        private val URI_ATTRIBUTE = Regex("""URI="([^"]*)"""")
        /** The root element of a DASH manifest, after the XML declaration and comments */
        private val MPD_ROOT = Regex("""^\s*(<\?xml[^>]*\?>\s*)?(<!--.*?-->\s*)*<(\w+:)?MPD[\s>]""", RegexOption.DOT_MATCHES_ALL)

        /** The playlist at [url] with the [text] (or a DASH manifest); null if it is none */
        fun parse(text: String, url: String): HlsPlaylist? {
            if (MPD_ROOT.containsMatchIn(text.removePrefix("﻿").take(4096))) return Dash
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull()?.removePrefix("﻿")?.startsWith("#EXTM3U") != true) return null
            val playlists = LinkedHashSet<String>()
            var duration = 0.0
            var segments = false
            var ended = false
            var streamInf = false
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF") -> streamInf = true
                    line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF") ->
                        URI_ATTRIBUTE.find(line)?.let { resolve(url, it.groupValues[1]) }?.let { playlists += it }
                    line.startsWith("#EXTINF:") -> {
                        segments = true
                        line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it > 0 }?.let { duration += it }
                    }
                    line.startsWith("#EXT-X-ENDLIST") -> ended = true
                    line.startsWith("#") -> {}
                    streamInf -> {
                        resolve(url, line)?.let { playlists += it }
                        streamInf = false
                    }
                }
            }
            return if (playlists.isNotEmpty() && !segments) Master(playlists) else Media(duration, ended)
        }

        private fun resolve(base: String, address: String): String? = try {
            URL(URL(base), address).toString()
        } catch (e: MalformedURLException) {
            null
        }
    }
}

/**
 * The HLS playlists of a page that were read ([read]), so the videos of the page are known by what their
 * playlists say and not by their addresses alone: which playlists are the qualities of a master, and how long
 * a stream lasts. The page loaded them already; they are read again only where the addresses leave it
 * unclear ([PageVideoList.unclearStreams]), at most [limit] for a page. [onRead] (in [scope]) after each one.
 */
class HlsPlaylists(
    private val scope: CoroutineScope,
    /** The text of the playlist, null if it can not be had */
    private val download: suspend (PageVideo) -> String?,
    private val limit: Int = 12,
    private val onRead: () -> Unit,
) {
    /** By address; null: no playlist could be read there */
    private val known = HashMap<String, HlsPlaylist?>()
    private val jobs = HashMap<String, Job>()

    operator fun get(url: String): HlsPlaylist? = known[url]

    /** Starts reading those of the [videos] that are HLS streams not read yet */
    fun read(videos: List<PageVideo>) {
        for (video in videos) {
            val url = video.url
            if (video.kind != PageVideo.Kind.HLS && video.kind != PageVideo.Kind.XML) continue
            if (url in known || url in jobs) continue
            if (known.size + jobs.size >= limit) return
            //started once it is kept: a scope that runs it at once finishes it here
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val playlist = download(video)?.let { HlsPlaylist.parse(it, url) }
                jobs.remove(url)
                known[url] = playlist
                onRead()
            }
            jobs[url] = job
            job.start()
        }
    }

    /** The page changes: what was read is forgotten and the reads in progress are dropped */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        known.clear()
    }

    companion object {
        private const val TAG = "HlsPlaylists"
        private const val TIMEOUT_MS = 10_000
        /** A media playlist of a long film with long segment addresses takes up to a few hundred KB */
        private const val MAX_SIZE = 1024 * 1024

        /** The playlist of the [video] as its frame gets it (its Referer, the User-Agent and [cookies]) */
        suspend fun download(video: PageVideo, cookies: String?): String? = withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = AppProxy.open(URL(video.url)) as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                video.pageUrl?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
                video.userAgent?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("User-Agent", it) }
                cookies?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
                if (connection.responseCode !in 200..299) return@withContext null
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_SIZE) return@withContext null
                    }
                }
                out.toString(Charsets.UTF_8.name())
            } catch (e: IOException) {
                Log.d(TAG, "Can not read ${video.url}: $e")
                null
            } catch (e: ClassCastException) {
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
