package com.phlox.tvwebbrowser.webengine

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * A video the page plays or loads that another app can play: a file, or the manifest of an HLS or DASH
 * stream (not `blob:` media, which only the page can give data to).
 */
data class PageVideo(
    val url: String,
    val kind: Kind,
    /** The title of the document of the frame that has it */
    val title: String? = null,
    /** The address of that frame: the Referer of its requests */
    val pageUrl: String? = null,
    /** The User-Agent of the engine */
    val userAgent: String? = null,
    /** From the media element that plays it, if any */
    val width: Int? = null,
    val height: Int? = null,
    /** Seconds; null when unknown or endless */
    val duration: Double? = null,
    /** The subtitle files of the media elements of its frame (the stream often carries none itself) */
    val subtitles: List<PageSubtitle> = emptyList(),
) {
    enum class Kind {
        FILE, HLS, DASH,
        /**
         * An XML file the page fetched, which may be the manifest of a DASH stream without its extension and type: the
         * engine reads it ([HlsPlaylists]), and [PageVideoList] gives it as [DASH] only if it is one, else never
         */
        XML
    }

    /** The MIME type players know it by */
    val mimeType: String
        get() = when (kind) {
            Kind.HLS -> "application/x-mpegURL"
            Kind.DASH, Kind.XML -> "application/dash+xml"
            Kind.FILE -> PageVideoUrls.fileMimeType(url)
        }
}

/**
 * A subtitle file of the page: a `<track>` of a media element, one the configuration of its player lists (PlayerJS),
 * or one it loaded. Many players keep the subtitles apart from the stream, so other players get them only this way
 */
data class PageSubtitle(
    val url: String,
    /** Its language (BCP 47), if the page tells it */
    val language: String? = null,
    /** Its name in the menu of the page */
    val label: String? = null,
    /** Shown now: by the engine, or the file the page loaded last (players load them when they are turned on) */
    val showing: Boolean = false,
) {
    /** By the extension: SubRip, SSA/ASS, TTML; else WebVTT, as `<track>` files are */
    val mimeType: String
        get() = when (url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()) {
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "ttml", "dfxp" -> "application/ttml+xml"
            else -> "text/vtt"
        }
}

/** A [WebEngine] that finds the videos of its page ([pageVideos]) */
interface PageVideosSupport {
    /** The videos of the page in the order they were found; the same flow for the engine's whole life */
    val pageVideos: StateFlow<List<PageVideo>>
    /**
     * The video among them that the media the keys control plays (the built-in player can go on with it), or
     * the videos it may be when that is not clear ([PageVideoMatch]); the same flow for the engine's whole life
     */
    val mediaVideo: StateFlow<PageVideoMatch>
}

/** What a media element plays, as media_controls.js reports it ([MediaReports.source]) */
data class MediaSource(
    /** Its http(s) address; null for media the page streams itself (MSE) and for unknown ones */
    val src: String?,
    /** The page streams it through Media Source Extensions (a `blob:` address) */
    val mse: Boolean,
)

/**
 * The video of the page a media element plays: [video] when it is known, else the [choices] it may be (the
 * streams of its frame, the likeliest first, with the durations their playlists tell), so the user can pick it;
 * [sameLength]: the addresses of the choices that last as long as the media
 */
data class PageVideoMatch(val video: PageVideo?, val choices: List<PageVideo> = emptyList(),
                          val sameLength: Set<String> = emptySet()) {
    companion object {
        val NONE = PageVideoMatch(null)

        /**
         * Among the videos of the frame of the media ([PageVideoList.videosOf]): the one at its address, else,
         * for media the page streams itself (MSE), the only stream (HLS or DASH) of the frame, or the only one
         * that lasts as long as the media ([duration], seconds) when the durations of all of them are known
         * ([playlists]); the others are [choices]
         */
        fun of(source: MediaSource?, frameVideos: List<PageVideo>, duration: Double? = null,
               playlists: (String) -> HlsPlaylist? = { null }): PageVideoMatch {
            if (source == null) return NONE
            source.src?.let { src -> frameVideos.firstOrNull { it.url == src }?.let { return PageVideoMatch(it) } }
            if (!source.mse) return NONE
            val streams = frameVideos.filter { it.kind != PageVideo.Kind.FILE }
            if (streams.size <= 1) return PageVideoMatch(streams.singleOrNull())
            val durations = streams.map { durationOf(it, playlists) }
            val known = streams.mapIndexed { i, stream -> durations[i]?.let { stream.copy(duration = it) } ?: stream }
            if (duration == null) return PageVideoMatch(null, known)
            val fitting = durations.map { it?.let { d -> fits(d, duration) } }
            if (fitting.count { it == true } == 1 && fitting.none { it == null }) {
                return PageVideoMatch(streams[fitting.indexOf(true)])
            }
            //those that last as long as the media first
            return PageVideoMatch(null, known.indices.sortedBy { if (fitting[it] == true) 0 else 1 }.map { known[it] },
                known.filterIndexed { i, _ -> fitting[i] == true }.map { it.url }.toSet())
        }

        private fun fits(a: Double, b: Double) = kotlin.math.abs(a - b) <= maxOf(2.0, 0.01 * maxOf(a, b))

        /**
         * How long the HLS [stream] lasts: its media playlist, or one of the playlists its master lists (the
         * page loads those it plays); null when unknown or for a live stream
         */
        private fun durationOf(stream: PageVideo, playlists: (String) -> HlsPlaylist?): Double? {
            if (stream.kind != PageVideo.Kind.HLS) return null
            val media = when (val playlist = playlists(stream.url)) {
                is HlsPlaylist.Media -> listOf(playlist)
                is HlsPlaylist.Master -> playlist.playlists.mapNotNull { playlists(it) as? HlsPlaylist.Media }
                HlsPlaylist.Dash, null -> emptyList()
            }
            return media.firstOrNull { it.ended && it.duration > 0 }?.duration
        }
    }
}

/** Null if the engine does not find the videos of pages */
val WebEngine.pageVideosSupport: PageVideosSupport?
    get() = this as? PageVideosSupport

/** Which addresses are videos */
object PageVideoUrls {
    private val FILE_TYPES = mapOf("mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm",
        "mkv" to "video/x-matroska", "mov" to "video/quicktime", "ogv" to "video/ogg", "avi" to "video/x-msvideo",
        "flv" to "video/x-flv", "3gp" to "video/3gpp")
    private val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
        "audio/x-mpegurl")
    private val XML_TYPES = setOf("application/xml", "text/xml")

    /**
     * The kind of the video at the http(s) [url], null if it is none: the manifests of HLS and DASH, by the
     * [type] of the response (its MIME type, where the engine tells it) or the extension (.m3u8, .mpd), and
     * the files media elements play ([fromMediaElement]). A file that the page fetched itself (fetch, XHR) is
     * rather a segment of a stream it plays, so it is none.
     */
    fun kindOf(url: String, fromMediaElement: Boolean, type: String? = null): PageVideo.Kind? {
        val extension = extensionOf(pathOf(url) ?: return null)
        val mime = type?.substringBefore(';')?.trim()?.lowercase()
        return when {
            mime in HLS_TYPES -> PageVideo.Kind.HLS
            mime == "application/dash+xml" -> PageVideo.Kind.DASH
            extension == "m3u8" -> PageVideo.Kind.HLS
            extension == "mpd" -> PageVideo.Kind.DASH
            //what a media element plays is a file, whatever its address looks like
            fromMediaElement -> PageVideo.Kind.FILE
            mime in XML_TYPES -> PageVideo.Kind.XML
            else -> null
        }
    }

    /** By the extension of the path; "video/\*" when it has none known */
    fun fileMimeType(url: String): String = FILE_TYPES[extensionOf(pathOf(url) ?: "")] ?: "video/*"

    /**
     * Whether the HLS playlist at [url] is a variant (a quality, the audio) of the one at [master]: players
     * load the master playlist first, then the playlists it lists, which lie on the same server in its folder
     * or below it, or in its folder with one more folder put in its path (`hls/<token>/` and
     * `hls/480/<token>/`). A second stream in the same folder is taken for a variant too. Numbers in the
     * names of folders are compared without their leading zeros: the variants of a master in `video01/` may
     * lie in `video1/`.
     */
    fun isVariantOf(url: String, master: String): Boolean {
        if (url == master) return false
        val (masterHost, masterDirs) = folderOf(master) ?: return false
        val (host, dirs) = folderOf(url) ?: return false
        if (host != masterHost) return false
        if (dirs.startsWith(masterDirs)) return true
        return dirs.indices.any { skipped -> dirs.filterIndexed { i, _ -> i != skipped }.startsWith(masterDirs) }
    }

    private fun List<String>.startsWith(prefix: List<String>) = size >= prefix.size && subList(0, prefix.size) == prefix

    /** The server and the folders of the path, their numbers without leading zeros */
    private fun folderOf(url: String): Pair<String, List<String>>? {
        val path = pathOf(url) ?: return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        return host to path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
            .map { it.replace(LEADING_ZEROS, "") }
    }

    private val LEADING_ZEROS = Regex("(?<![0-9])0+(?=[0-9])")

    /** The path of an http(s) address with a host, null for other addresses */
    private fun pathOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(schemeEnd + 3)
        val hostEnd = rest.indexOfAny(charArrayOf('/', '?', '#'))
        val host = if (hostEnd < 0) rest else rest.substring(0, hostEnd)
        if (host.isEmpty()) return null
        if (hostEnd < 0 || rest[hostEnd] != '/') return ""
        return rest.substring(hostEnd).substringBefore('?').substringBefore('#')
    }

    private fun extensionOf(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
}

/**
 * The videos of a page, from the reports of its frames ([F]: how a frame is known, compared by identity):
 * each report replaces the videos of its frame. One address is one video, kept at the place where it was
 * first found, with what the frames tell about it; the variants of an HLS stream are not listed apart: those
 * a master playlist that was read lists ([playlists]), and by their addresses ([PageVideoUrls.isVariantOf]).
 * The list keeps at most [limit] videos.
 */
class PageVideoList<F : Any>(private val limit: Int = 50, private val playlists: (String) -> HlsPlaylist? = { null }) {
    private val frames = LinkedHashMap<F, List<PageVideo>>()
    /** The order the addresses were found in, also after their frames are gone, until the page changes */
    private val order = LinkedHashMap<String, Int>()

    /** @return the videos of the page */
    fun onFrameVideos(frame: F, videos: List<PageVideo>): List<PageVideo> {
        if (videos.isEmpty()) frames.remove(frame) else frames[frame] = videos
        for (video in videos) if (video.url !in order) order[video.url] = order.size
        return videos()
    }

    /** The main frame starts another page */
    fun clear() {
        frames.clear()
        order.clear()
    }

    fun videos(): List<PageVideo> {
        val byUrl = LinkedHashMap<String, PageVideo>()
        for (video in resolved(frames.values.flatten())) {
            val known = byUrl[video.url]
            byUrl[video.url] = if (known == null) video else merge(known, video)
        }
        return withoutVariants(byUrl.values.sortedBy { order[it.url] ?: Int.MAX_VALUE }).take(limit)
    }

    /** The videos one frame reported, the variants of an HLS stream as one */
    fun videosOf(frame: F): List<PageVideo> = withoutVariants(resolved(frames[frame] ?: emptyList()))

    /**
     * The files to read ([HlsPlaylists]): the XML files the frames fetched (DASH manifests or not), and the HLS
     * playlists where the addresses leave the streams of a frame unclear: all of those of each frame that has more
     * than one stream by its addresses (a master with qualities that lie elsewhere, or another stream, an ad, whose
     * duration tells it apart)
     */
    fun unclearStreams(): List<PageVideo> = frames.values.flatMap { videos ->
        val xml = videos.filter { it.kind == PageVideo.Kind.XML }
        val streams = withoutVariants(resolved(videos)).count { it.kind != PageVideo.Kind.FILE }
        xml + if (streams > 1) videos.filter { it.kind == PageVideo.Kind.HLS } else emptyList()
    }

    /** The XML files that were read: the DASH manifests among them as DASH, the others left out */
    private fun resolved(videos: List<PageVideo>): List<PageVideo> = videos.mapNotNull { video ->
        if (video.kind != PageVideo.Kind.XML) video
        else if (playlists(video.url) == HlsPlaylist.Dash) video.copy(kind = PageVideo.Kind.DASH)
        else null
    }

    private fun withoutVariants(videos: List<PageVideo>): List<PageVideo> {
        //listed by a master that was read
        val listed = videos.flatMapTo(HashSet()) { (playlists(it.url) as? HlsPlaylist.Master)?.playlists ?: emptySet() }
        val masters = ArrayList<String>()
        return videos.filter { video ->
            if (video.kind != PageVideo.Kind.HLS) return@filter true
            if (video.url in listed || masters.any { PageVideoUrls.isVariantOf(video.url, it) }) return@filter false
            masters += video.url
            true
        }
    }

    /** What one frame does not tell about a video, another one may */
    private fun merge(a: PageVideo, b: PageVideo) = a.copy(
        title = a.title ?: b.title, pageUrl = a.pageUrl ?: b.pageUrl, userAgent = a.userAgent ?: b.userAgent,
        width = a.width ?: b.width, height = a.height ?: b.height, duration = a.duration ?: b.duration,
        subtitles = a.subtitles.ifEmpty { b.subtitles })
}

/** The reports of page_videos.js, the script both engines run in every frame */
object PageVideoReports {
    /**
     * The videos of the frame of the [report]; addresses that are no videos ([PageVideoUrls]) are left out. The
     * subtitles of the frame go with each of them: the page plays a stream it loads through a media element, and
     * which one is not told here
     */
    fun parse(report: JSONObject): List<PageVideo> {
        val items = report.optJSONArray("videos") ?: return emptyList()
        val title = report.optString("title").ifBlank { null }
        val page = report.optString("page").ifBlank { null }
        val userAgent = report.optString("ua").ifBlank { null }
        val subtitles = subtitles(report)
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url")
            val type = item.optString("type").ifBlank { null }
            val kind = PageVideoUrls.kindOf(url, item.optBoolean("element"), type) ?: return@mapNotNull null
            PageVideo(url, kind, title, page, userAgent,
                width = item.optInt("width").takeIf { it > 0 },
                height = item.optInt("height").takeIf { it > 0 },
                duration = item.optDouble("duration").takeIf { it.isFinite() && it > 0 },
                subtitles = subtitles)
        }
    }

    private fun subtitles(report: JSONObject): List<PageSubtitle> {
        val items = report.optJSONArray("subtitles") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@mapNotNull null
            PageSubtitle(url, item.optNullableString("lang"), item.optNullableString("label"), item.optBoolean("showing"))
        }.distinctBy { it.url }
    }

    /** JSON null and blank strings as null (optString gives "null" for a JSON null) */
    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }
}
