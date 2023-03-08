package com.phlox.tvwebbrowser.utils

import android.util.JsonReader
import android.webkit.MimeTypeMap
import java.io.BufferedReader
import java.io.Reader
import java.net.URL
import java.util.regex.Pattern


class FaviconExtractor {
    companion object {
        const val DEFAULT_ICON_SRC = "/favicon.ico"
        const val DEFAULT_ICON_TYPE = "image/x-icon"
        const val DEFAULT_ICON_SIZE = 16
        const val DEFAULT_ICON_SIZE_STRING = "${DEFAULT_ICON_SIZE}x${DEFAULT_ICON_SIZE}"
    }

    private val headerClosingTagsPattern: Pattern = Pattern.compile("<\\s*(?:body|/\\s*head)(?:\\s+|>)")
    private val tagsPattern: Pattern = Pattern.compile("<(?!!)(?!/)\\s*([a-zA-Z\\d]+)((?s:.)*?)>")
    private val attributePattern: Pattern = Pattern.compile("(\\S+)=\\s*['\"]?([^>\"'\\s]+)['\"]?")

    class IconInfo (
        var src: String,
        var type: String? = null,
        var rel: String? = null,
        sizes: String? = null,
        baseURL: URL? = null
    ) {
        var width: Int = 0
        var height: Int = 0
        init {
            if (sizes != null) {
                try {
                    val sizesArr = sizes.split("x")
                    width = Integer.parseUnsignedInt(sizesArr[0])
                    height = Integer.parseUnsignedInt(sizesArr[1])
                } catch (e: Exception) {
                    //it is ok, just leave default values of w and h
                }
            }
            if (baseURL != null) {
                src = URL(baseURL, src).toString()
            }
            if (type == null) {
                val extension = MimeTypeMap.getFileExtensionFromUrl(src)
                if (extension != null) {
                    type = guessIconType(extension)
                }
            }
        }

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
     * Will also collect icons from web manifest
     *
     * @throws java.io.IOException
     */
    fun extractFavIconsFromURL(url: URL): ArrayList<IconInfo> {
        val (result, manifestHref) = url.openConnection().inputStream.bufferedReader().use { extractFavIconsFromHTML(url, it) }
        if (manifestHref != null) {
            val manifestURL = URL(url, manifestHref)
            try {
                val manifestIcons = manifestURL.openConnection().inputStream.bufferedReader()
                    .use { extractFavIconsFromWebManifest(manifestURL, it) }
                result.addAll(manifestIcons)
            } catch (e: Exception) {
                //shit happens, but I don't think it's too important here
                e.printStackTrace()
            }
        }
        if (result.isEmpty()) {
            result.add(IconInfo(
                "/favicon.ico",
                DEFAULT_ICON_TYPE,
                null,
                DEFAULT_ICON_SIZE_STRING,
                url
            ))
        }
        return result
    }

    fun extractFavIconsFromWebManifest(manifestURL: URL?, manifest: Reader): ArrayList<IconInfo> {
        val iconInfos = ArrayList<IconInfo>()
        val jsonReader = JsonReader(manifest)
        jsonReader.use {
            jsonReader.beginObject()
            while (jsonReader.hasNext()) {
                val name = jsonReader.nextName()
                if (name == "icons") {
                    jsonReader.beginArray()
                    while (jsonReader.hasNext()) {
                        jsonReader.beginObject()
                        var src: String? = null
                        var type: String? = null
                        var sizes: String? = null
                        while (jsonReader.hasNext()) {
                            when (jsonReader.nextName()) {
                                "src" -> {
                                    src = jsonReader.nextString()
                                }
                                "sizes" -> {
                                    sizes = jsonReader.nextString()
                                }
                                "type" -> {
                                    type = jsonReader.nextString()
                                }
                            }
                        }
                        jsonReader.endObject()
                        if (src != null) {
                            iconInfos.add(IconInfo(src, type, null, sizes, manifestURL))
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

        val headerPartOfHTML = StringBuilder()
        var line = html.readLine()
        while (line != null) {
            headerPartOfHTML.appendLine(line)
            val matcher = headerClosingTagsPattern.matcher(line)
            if (matcher.find()) {
                break
            }
            line = html.readLine()
        }

        val matcher = tagsPattern.matcher(headerPartOfHTML.toString())
        while (matcher.find()) {
            val tagName = matcher.group(1) ?: continue
            //println("tag name: $tagName")
            when (tagName) {
                "link" -> {
                    val attributes = matcher.group(2) ?: continue
                    //println("     rest of the tag: $attributes")
                    val attributeMatcher = attributePattern.matcher(attributes)
                    var rel: String? = null
                    var href: String? = null
                    var sizes: String? = null
                    var type: String? = null
                    while (attributeMatcher.find()) {
                        val attributeName = attributeMatcher.group(1) ?: continue
                        val attributeValue = attributeMatcher.group(2) ?: continue
                        //println("         attribute name: $attributeName    value: $attributeValue")
                        when (attributeName) {
                            "rel" -> rel = attributeValue
                            "href" -> href = attributeValue
                            "sizes" -> sizes = attributeValue
                            "type" -> type = attributeValue
                        }
                    }
                    if (href == null || rel == null) {
                        continue
                    }
                    if (rel.equals("icon", true) ||
                        rel.equals("apple-touch-icon", true) ||
                        rel.equals("shortcut icon", true) ||
                        rel.equals("shortcut", true) ||
                        rel.equals("fluid-icon", true)) {
                        if (sizes == null && rel.equals("apple-touch-icon", true)) {
                            sizes = "180x180"
                        }
                        iconInfos.add(IconInfo(href, type, rel, sizes, baseURL))
                    } else if (rel.equals("manifest", true)) {
                        manifestHref = href
                    }
                }
                "body" -> {
                    break
                }
            }
        }


        return Pair(iconInfos, manifestHref)
    }
}