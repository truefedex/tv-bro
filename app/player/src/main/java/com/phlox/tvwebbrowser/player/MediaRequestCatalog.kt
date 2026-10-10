package com.phlox.tvwebbrowser.player

import java.net.URI
import java.util.Locale

/** Ephemeral, bounded observations of requests made by the browser, never a site allow-list. */
class MediaRequestCatalog(private val now: () -> Long = System::currentTimeMillis) {
    enum class Kind(val mime: String?) {
        PROGRESSIVE(null), HLS("application/x-mpegURL"), DASH("application/dash+xml")
    }
    data class Request(
        val url: String,
        val page: String,
        val frame: String,
        val privateMode: Boolean,
        val kind: Kind,
        val headers: Map<String, String> = emptyMap(),
        val children: Set<String> = emptySet(),
        val protectedContent: Boolean = false,
        val time: Long = 0,
        val tabId: Int = -1
    )
    sealed class Selection {
        data class Resolved(val request: Request) : Selection()
        data class Ambiguous(val candidates: List<Request>) : Selection()
        data class Unavailable(val reason: String) : Selection()
    }
    private val requests = LinkedHashMap<String, Request>()

    @Synchronized
    fun observe(request: Request) {
        if (!isHttp(request.url) || !isHttp(request.page) ||
            maxOf(request.url.length, request.page.length, request.frame.length) > 16384) return
        prune()
        val key = key(request.url, request.page, request.privateMode, request.tabId)
        val old = requests[key]
        requests[key] = request.copy(headers = sanitizeHeaders(request.headers),
            children = if (request.children.isEmpty()) old?.children.orEmpty() else request.children,
            protectedContent = request.protectedContent || old?.protectedContent == true,
            time = now())
        while (requests.size > 96) {
            val expendable = requests.entries.firstOrNull { it.value.kind == Kind.PROGRESSIVE }?.key
            requests.remove(expendable ?: requests.keys.first())
        }
    }

    @Synchronized
    fun annotate(request: Request, children: Set<String>, protectedContent: Boolean) {
        val key = key(request.url, request.page, request.privateMode, request.tabId)
        requests[key]?.let { requests[key] = it.copy(children = children, protectedContent = protectedContent) }
    }

    @Synchronized
    fun select(source: String, page: String, privateMode: Boolean, tabId: Int = -1, frame: String? = null): Selection {
        prune()
        if (!isHttp(page)) return Selection.Unavailable("not-web-content")
        if (isHttp(source)) {
            val exact = requests.values.lastOrNull { documentKey(it.url) == documentKey(source) &&
                it.privateMode == privateMode && (if (tabId >= 0) it.tabId == tabId else documentKey(it.page) == documentKey(page)) }
            if (exact?.protectedContent == true) return Selection.Unavailable("protected-content")
            return Selection.Resolved(exact ?: Request(source, page, page, privateMode, inferKind(source, null), tabId = tabId))
        }
        if (!source.startsWith("blob:")) return Selection.Unavailable("unsupported-scheme")
        val frameOrigin = frame?.let(::origin) ?: origin(source.removePrefix("blob:")) ?: return Selection.Unavailable("opaque-blob")
        val matching = requests.values.filter {
            (if (tabId >= 0) it.tabId == tabId else documentKey(it.page) == documentKey(page)) && it.privateMode == privateMode &&
                origin(it.frame) == frameOrigin && it.kind != Kind.PROGRESSIVE
        }
        val exactFrame = frame?.let { value -> matching.filter { documentKey(it.frame) == documentKey(value) } }.orEmpty()
        val candidates = if (exactFrame.isNotEmpty()) exactFrame else matching
        if (frame != null && exactFrame.isEmpty() && matching.map { documentKey(it.frame) }.distinct().size > 1)
            return Selection.Unavailable("ambiguous-frame")
        val children = candidates.flatMap { it.children }.map(::documentKey).toSet()
        val roots = candidates.filter { documentKey(it.url) !in children }
        return when {
            roots.isEmpty() -> Selection.Unavailable("unresolved-media-source")
            roots.size > 1 -> Selection.Ambiguous(roots)
            roots.single().protectedContent -> Selection.Unavailable("protected-content")
            else -> Selection.Resolved(roots.single())
        }
    }

    @Synchronized
    fun snapshot(page: String, privateMode: Boolean, tabId: Int = -1): List<Request> {
        prune()
        return requests.values.filter { (if (tabId >= 0) it.tabId == tabId else documentKey(it.page) == documentKey(page)) && it.privateMode == privateMode }
    }

    @Synchronized
    fun clearPage(page: String) {
        requests.entries.removeAll { documentKey(it.value.page) == documentKey(page) }
    }
    @Synchronized
    fun navigateTab(tabId: Int, page: String) {
        requests.entries.removeAll { it.value.tabId == tabId && documentKey(it.value.page) != documentKey(page) }
    }

    private fun prune() { requests.entries.removeAll { now() - it.value.time > 30 * 60 * 1000L } }
    private fun key(url: String, page: String, privateMode: Boolean, tabId: Int) = "$privateMode|$tabId|${documentKey(page)}|${documentKey(url)}"

    companion object {
        val shared = MediaRequestCatalog()
        fun isHttp(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        }.getOrDefault(false)
        fun documentKey(url: String) = url.substringBefore('#')
        fun origin(url: String): String? = runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val port = if (uri.port < 0 || scheme == "https" && uri.port == 443 || scheme == "http" && uri.port == 80) "" else ":${uri.port}"
            "$scheme://$host$port"
        }.getOrNull()
        fun inferKind(url: String, mime: String?): Kind {
            val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            return when {
                type in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl") || path.endsWith(".m3u8") -> Kind.HLS
                type == "application/dash+xml" || path.endsWith(".mpd") -> Kind.DASH
                else -> Kind.PROGRESSIVE
            }
        }
        fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> = headers.entries
            .filter { (name, value) -> name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")) &&
                value.length <= 8192 && '\r' !in value && '\n' !in value && name.lowercase(Locale.ROOT) !in
                setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding", "range", "proxy-authorization", "proxy-authenticate") }
            .take(32).associate { it.key to it.value }
    }
}

/** Credentials stay within the observed origin and path scope, including after redirects. */
class MediaRequestContext(val source: MediaRequestCatalog.Request, val observations: List<MediaRequestCatalog.Request>,
    private val userAgent: String) {
    fun headersFor(url: String): Map<String, String> {
        val targetOrigin = MediaRequestCatalog.origin(url)
        fun normalizedPath(value: String): String = runCatching {
            URI(null, null, URI(value).path.orEmpty(), null).normalize().path.orEmpty()
        }.getOrDefault("")
        val targetPath = normalizedPath(url)
        val candidates = (observations + source).filter { MediaRequestCatalog.origin(it.url) == targetOrigin }
        val exact = candidates.lastOrNull { MediaRequestCatalog.documentKey(it.url) == MediaRequestCatalog.documentKey(url) }
        val scoped = exact ?: candidates.filter {
            val directory = normalizedPath(it.url).substringBeforeLast('/', "") + "/"
            directory.isNotEmpty() && targetPath.startsWith(directory)
        }.maxByOrNull { URI(it.url).path.orEmpty().length }
        val defaults = linkedMapOf("User-Agent" to userAgent, "Accept" to "*/*")
        MediaRequestCatalog.origin(source.frame).takeIf { it != null && !(it.startsWith("https:") && url.startsWith("http:")) }
            ?.let { defaults["Referer"] = "$it/" }
        scoped?.headers?.forEach { (key, value) ->
            defaults.keys.firstOrNull { it.equals(key, true) }?.let { defaults.remove(it) }
            defaults[key] = value
        }
        return defaults
    }
}
