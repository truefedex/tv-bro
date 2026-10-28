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
) {
    enum class Kind { FILE, HLS, DASH }

    /** The MIME type players know it by */
    val mimeType: String
        get() = when (kind) {
            Kind.HLS -> "application/x-mpegURL"
            Kind.DASH -> "application/dash+xml"
            Kind.FILE -> PageVideoUrls.fileMimeType(url)
        }
}

/** A [WebEngine] that finds the videos of its page ([pageVideos]) */
interface PageVideosSupport {
    /** The videos of the page in the order they were found; the same flow for the engine's whole life */
    val pageVideos: StateFlow<List<PageVideo>>
    /**
     * The video among them that the media the keys control plays ([PageVideoMatch]; the built-in player can
     * go on with it), null if none is known; the same flow for the engine's whole life
     */
    val mediaVideo: StateFlow<PageVideo?>
}

/** What a media element plays, as media_controls.js reports it ([MediaReports.source]) */
data class MediaSource(
    /** Its http(s) address; null for media the page streams itself (MSE) and for unknown ones */
    val src: String?,
    /** The page streams it through Media Source Extensions (a `blob:` address) */
    val mse: Boolean,
)

/** The video of the page a media element plays */
object PageVideoMatch {
    /**
     * Among the videos of the frame of the media: the one at its address, else, for media the page streams
     * itself (MSE), the only stream (HLS or DASH) of the frame; null when it is not known which
     */
    fun of(source: MediaSource?, frameVideos: List<PageVideo>): PageVideo? {
        if (source == null) return null
        source.src?.let { src -> frameVideos.firstOrNull { it.url == src }?.let { return it } }
        if (!source.mse) return null
        return frameVideos.filter { it.kind != PageVideo.Kind.FILE }.singleOrNull()
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

    /**
     * The kind of the video at the http(s) [url], null if it is none: the manifests of HLS (.m3u8) and
     * DASH (.mpd), and the files media elements play ([fromMediaElement]). A file that the page fetched
     * itself (fetch, XHR) is rather a segment of a stream it plays, so it is none.
     */
    fun kindOf(url: String, fromMediaElement: Boolean): PageVideo.Kind? {
        val extension = extensionOf(pathOf(url) ?: return null)
        return when {
            extension == "m3u8" -> PageVideo.Kind.HLS
            extension == "mpd" -> PageVideo.Kind.DASH
            //what a media element plays is a file, whatever its address looks like
            fromMediaElement -> PageVideo.Kind.FILE
            else -> null
        }
    }

    /** By the extension of the path; "video/\*" when it has none known */
    fun fileMimeType(url: String): String = FILE_TYPES[extensionOf(pathOf(url) ?: "")] ?: "video/*"

    /**
     * Whether the HLS playlist at [url] is a variant (a quality, the audio) of the one at [master]: players
     * load the master playlist first, then the playlists it lists, which lie on the same server in its folder
     * or below it, or in its folder with one more folder put in its path (`hls/<token>/` and
     * `hls/480/<token>/`). A second stream in the same folder is taken for a variant too.
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

    /** The server and the folders of the path */
    private fun folderOf(url: String): Pair<String, List<String>>? {
        val path = pathOf(url) ?: return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        return host to path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
    }

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
 * first found, with what the frames tell about it; the variants of an HLS stream are not listed apart
 * ([PageVideoUrls.isVariantOf]). The list keeps at most [limit] videos.
 */
class PageVideoList<F : Any>(private val limit: Int = 50) {
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
        for (video in frames.values.flatten()) {
            val known = byUrl[video.url]
            byUrl[video.url] = if (known == null) video else merge(known, video)
        }
        return withoutVariants(byUrl.values.sortedBy { order[it.url] ?: Int.MAX_VALUE }).take(limit)
    }

    /** The videos one frame reported, the variants of an HLS stream as one */
    fun videosOf(frame: F): List<PageVideo> = withoutVariants(frames[frame] ?: emptyList())

    private fun withoutVariants(videos: List<PageVideo>): List<PageVideo> {
        val masters = ArrayList<String>()
        return videos.filter { video ->
            if (video.kind != PageVideo.Kind.HLS) return@filter true
            if (masters.any { PageVideoUrls.isVariantOf(video.url, it) }) return@filter false
            masters += video.url
            true
        }
    }

    /** What one frame does not tell about a video, another one may */
    private fun merge(a: PageVideo, b: PageVideo) = a.copy(
        title = a.title ?: b.title, pageUrl = a.pageUrl ?: b.pageUrl, userAgent = a.userAgent ?: b.userAgent,
        width = a.width ?: b.width, height = a.height ?: b.height, duration = a.duration ?: b.duration)
}

/** The reports of page_videos.js, the script both engines run in every frame */
object PageVideoReports {
    /** The videos of the frame of the [report]; addresses that are no videos ([PageVideoUrls]) are left out */
    fun parse(report: JSONObject): List<PageVideo> {
        val items = report.optJSONArray("videos") ?: return emptyList()
        val title = report.optString("title").ifBlank { null }
        val page = report.optString("page").ifBlank { null }
        val userAgent = report.optString("ua").ifBlank { null }
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url")
            val kind = PageVideoUrls.kindOf(url, item.optBoolean("element")) ?: return@mapNotNull null
            PageVideo(url, kind, title, page, userAgent,
                width = item.optInt("width").takeIf { it > 0 },
                height = item.optInt("height").takeIf { it > 0 },
                duration = item.optDouble("duration").takeIf { it.isFinite() && it > 0 })
        }
    }
}
