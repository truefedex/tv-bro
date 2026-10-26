package com.phlox.tvwebbrowser.utils

import com.phlox.tvwebbrowser.net.AppProxy
import android.util.JsonReader
import android.util.JsonToken
import android.webkit.MimeTypeMap
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.regex.Pattern


class FaviconExtractor {
    companion object {
        const val DEFAULT_ICON_SRC = "/favicon.ico"
        const val DEFAULT_ICON_TYPE = "image/x-icon"
        const val MAX_HTML_HEADER_CHARS = 256 * 1024
        /** The size of an apple-touch-icon that tells none (what iOS asks for) */
        const val APPLE_TOUCH_ICON_SIZE = 180
        const val TIMEOUT_MS = 10_000
        private const val MAX_REDIRECTS = 5
        /** Some servers give other agents (Dalvik/…) a page without icons or none at all */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"

        private val ICON_RELS = setOf("icon", "apple-touch-icon", "apple-touch-icon-precomposed", "fluid-icon")

        /**
         * Opens [url] with timeouts and the browser's User-Agent, following redirects also from http to
         * https (HttpURLConnection follows only those within one protocol). `connection.url` is the
         * address it ended at.
         */
        fun open(url: URL, accept: String? = null): URLConnection {
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                val connection = AppProxy.open(current)
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (accept != null) connection.setRequestProperty("Accept", accept)
                if (connection !is HttpURLConnection) return connection
                val code = connection.responseCode
                val location = if (code in 300..399) connection.getHeaderField("Location") else null
                if (location == null) return connection
                val next = URL(connection.url, location)
                connection.disconnect()
                if (!next.protocol.equals("http", true) && !next.protocol.equals("https", true)) {
                    throw IOException("Redirect to $next")
                }
                current = next
            }
            throw IOException("Too many redirects: $url")
        }

        /**
         * The icons in the order to try them for a picture of [target] pixels: the smallest of those
         * at least that big (downscaled pictures stay sharp), then the biggest of those from [minSharp],
         * then those of unknown size (manifest, `<link>`, `/favicon.ico`), then the smaller ones;
         * SVG is left out (BitmapFactory can not decode it). Maskable icons (with a padding) go after
         * the others of their group.
         */
        fun rank(icons: List<IconInfo>, target: Int, minSharp: Int): List<IconInfo> {
            val usable = icons.filter { !it.isSvg }.distinctBy { it.src }
            val bigEnough = usable.filter { it.side >= target }
                .sortedWith(compareBy<IconInfo> { it.maskable }.thenBy { it.side })
            val sharp = usable.filter { it.side in minSharp until target }
                .sortedWith(compareBy<IconInfo> { it.maskable }.thenByDescending { it.side })
            val unknown = usable.filter { it.side == 0 }
                .sortedWith(compareBy<IconInfo> { it.source.ordinal }.thenBy { it.maskable })
            val small = usable.filter { it.side in 1 until minSharp }.sortedByDescending { it.side }
            return bigEnough + sharp + unknown + small
        }

        private fun decodeEntities(value: String) = if (value.contains('&')) {
            value.replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"")
        } else value
    }

    /** Where an icon was found, in the order icons of unknown size are tried */
    enum class Source { MANIFEST, LINK, DEFAULT }

    private val headerClosingTagsPattern: Pattern = Pattern.compile("<\\s*(?:body|/\\s*head)(?:\\s+|>)", Pattern.CASE_INSENSITIVE)
    private val tagsPattern: Pattern = Pattern.compile("<(?!!)(?!/)\\s*([a-zA-Z\\d]+)((?s:.)*?)>")
    /** name=value with the value in double or single quotes (spaces included) or without them */
    private val attributePattern: Pattern = Pattern.compile("([^\\s=/>\"']+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

    class IconInfo (
        var src: String,
        var type: String? = null,
        var rel: String? = null,
        sizes: String? = null,
        baseURL: URL? = null,
        val source: Source = Source.LINK,
        /** Only for the maskable purpose of a manifest: the picture has a padding around it */
        val maskable: Boolean = false
    ) {
        /** The biggest of the sizes given, 0 when they are unknown ("any", none) */
        var width: Int = 0
        var height: Int = 0
        init {
            if (sizes != null) {
                for (size in sizes.trim().split(Regex("\\s+"))) {
                    val parts = size.lowercase().split('x')
                    if (parts.size != 2) continue
                    val w = parts[0].toIntOrNull() ?: continue
                    val h = parts[1].toIntOrNull() ?: continue
                    if (w > 0 && h > 0 && maxOf(w, h) > maxOf(width, height)) {
                        width = w
                        height = h
                    }
                }
            }
            if (baseURL != null && !src.startsWith("data:", true)) {
                src = URL(baseURL, src).toString()
            }
            if (type == null) {
                val extension = MimeTypeMap.getFileExtensionFromUrl(src)
                if (extension != null) {
                    type = guessIconType(extension)
                }
            }
        }

        val side: Int get() = maxOf(width, height)

        val isSvg: Boolean get() = type?.contains("svg", true) == true ||
                src.substringBefore('?').substringBefore('#').endsWith(".svg", true) ||
                src.startsWith("data:image/svg", true)

        private fun guessIconType(iconExtension: String): String? {
            return if (iconExtension.equals("png", true)) {
                "image/png"
            } else if (iconExtension.equals("jpg", true)) {
                "image/jpeg"
            } else if (iconExtension.equals("ico", true)) {
                DEFAULT_ICON_TYPE
            } else if (iconExtension.equals("gif", true)) {
                "image/gif"
            } else if (iconExtension.equals("svg", true)) {
                "image/svg+xml"
            } else null
        }
    }

    /**
     * Downloads HTML by provided @param url, traverses all <link> tags and collects all found
     * icon as @return List<IconInfo>
     * Will also collect icons from web manifest. `/favicon.ico` of the site is always the last one.
     *
     * @throws java.io.IOException when the page can not be reached
     */
    fun extractFavIconsFromURL(url: URL): ArrayList<IconInfo> {
        val connection = open(url, "text/html,application/xhtml+xml,*/*;q=0.8")
        //relative addresses are of the page the redirects ended at
        val pageURL = connection.url
        val code = (connection as? HttpURLConnection)?.responseCode ?: 200
        val contentType = connection.contentType
        val (result, manifestHref) = if (code >= 400) {
            (connection as HttpURLConnection).errorStream?.close()
            Pair(ArrayList<IconInfo>(), null)
        } else if (contentType != null && !contentType.contains("html", true)) {
            //not a web page (file download, media stream, etc.), nothing to parse here
            connection.getInputStream().close()
            Pair(ArrayList<IconInfo>(), null)
        } else {
            connection.getInputStream().bufferedReader().use { extractFavIconsFromHTML(pageURL, it) }
        }
        if (manifestHref != null) {
            try {
                val manifestConnection = open(URL(pageURL, manifestHref), "application/manifest+json,application/json,*/*;q=0.8")
                val manifestURL = manifestConnection.url
                val manifestIcons = manifestConnection.getInputStream().bufferedReader()
                    .use { extractFavIconsFromWebManifest(manifestURL, it) }
                result.addAll(manifestIcons)
            } catch (e: Exception) {
                //the icons of the page are enough then
                e.printStackTrace()
            }
        }
        val defaultIcon = IconInfo(DEFAULT_ICON_SRC, DEFAULT_ICON_TYPE, null, null, pageURL, Source.DEFAULT)
        if (result.none { it.src == defaultIcon.src }) {
            result.add(defaultIcon)
        }
        return result
    }

    fun extractFavIconsFromWebManifest(manifestURL: URL?, manifest: Reader): ArrayList<IconInfo> {
        val iconInfos = ArrayList<IconInfo>()
        val jsonReader = JsonReader(manifest)
        jsonReader.isLenient = true
        jsonReader.use {
            jsonReader.beginObject()
            while (jsonReader.hasNext()) {
                val name = jsonReader.nextName()
                if (name == "icons" && jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        if (jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                            jsonReader.skipValue()
                            continue
                        }
                        jsonReader.beginObject()
                        var src: String? = null
                        var type: String? = null
                        var sizes: String? = null
                        var purpose: String? = null
                        while (jsonReader.hasNext()) {
                            val field = jsonReader.nextName()
                            if (jsonReader.peek() != JsonToken.STRING) {
                                jsonReader.skipValue()
                                continue
                            }
                            when (field) {
                                "src" -> src = jsonReader.nextString()
                                "sizes" -> sizes = jsonReader.nextString()
                                "type" -> type = jsonReader.nextString()
                                "purpose" -> purpose = jsonReader.nextString()
                                else -> jsonReader.skipValue()
                            }
                        }
                        jsonReader.endObject()
                        if (src != null) {
                            val purposes = purpose?.lowercase()?.split(Regex("\\s+")) ?: emptyList()
                            val maskable = purposes.isNotEmpty() && purposes.none { it == "any" }
                            try {
                                iconInfos.add(IconInfo(src, type, null, sizes, manifestURL, Source.MANIFEST, maskable))
                            } catch (e: Exception) {
                                //a malformed address: the other icons are still good
                            }
                        }
                    }
                    jsonReader.endArray()
                    return@use
                } else {
                    jsonReader.skipValue()
                }
            }
            jsonReader.endObject()
        }
        return iconInfos
    }

    /**
     * @return @kotlin.Pair of list of IconInfo and href of web manifest (if found any)
     *
     * @throws java.io.IOException
     */
    fun extractFavIconsFromHTML(baseURL: URL?, html: BufferedReader): Pair<ArrayList<IconInfo>, String?> {
        val iconInfos = ArrayList<IconInfo>()
        var manifestHref: String? = null

        //read by chunks with a hard limit: the page may be huge, have no line breaks or not be HTML at all
        val headerPartOfHTML = StringBuilder()
        val buffer = CharArray(8 * 1024)
        val headerClosingMatcher = headerClosingTagsPattern.matcher("")
        try {
            while (headerPartOfHTML.length < MAX_HTML_HEADER_CHARS) {
                val count = html.read(buffer, 0, minOf(buffer.size, MAX_HTML_HEADER_CHARS - headerPartOfHTML.length))
                if (count == -1) {
                    break
                }
                //search a bit before the new chunk to find a closing tag split between chunks
                val searchFrom = maxOf(0, headerPartOfHTML.length - 32)
                headerPartOfHTML.appendRange(buffer, 0, count)
                headerClosingMatcher.reset(headerPartOfHTML)
                if (headerClosingMatcher.find(searchFrom)) {
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(iconInfos, null)
        }

        val matcher = tagsPattern.matcher(headerPartOfHTML.toString())
        while (matcher.find()) {
            val tagName = matcher.group(1) ?: continue
            when {
                tagName.equals("link", true) -> {
                    val attributes = matcher.group(2) ?: continue
                    val attributeMatcher = attributePattern.matcher(attributes)
                    var rel: String? = null
                    var href: String? = null
                    var sizes: String? = null
                    var type: String? = null
                    while (attributeMatcher.find()) {
                        val attributeName = attributeMatcher.group(1) ?: continue
                        val attributeValue = decodeEntities(
                            (attributeMatcher.group(2) ?: attributeMatcher.group(3) ?: attributeMatcher.group(4) ?: continue).trim())
                        when (attributeName.lowercase()) {
                            "rel" -> rel = attributeValue
                            "href" -> href = attributeValue
                            "sizes" -> sizes = attributeValue
                            "type" -> type = attributeValue
                        }
                    }
                    if (href.isNullOrEmpty() || rel == null) {
                        continue
                    }
                    val relTokens = rel.lowercase().split(Regex("\\s+"))
                    if (relTokens.any { it in ICON_RELS }) {
                        if (sizes == null && relTokens.any { it.startsWith("apple-touch-icon") }) {
                            sizes = "${APPLE_TOUCH_ICON_SIZE}x$APPLE_TOUCH_ICON_SIZE"
                        }
                        try {
                            iconInfos.add(IconInfo(href, type, rel, sizes, baseURL))
                        } catch (e: Exception) {
                            //a malformed address: the other icons are still good
                        }
                    } else if (relTokens.contains("manifest")) {
                        manifestHref = href
                    }
                }
                tagName.equals("body", true) -> {
                    break
                }
            }
        }

        return Pair(iconInfos, manifestHref)
    }
}
