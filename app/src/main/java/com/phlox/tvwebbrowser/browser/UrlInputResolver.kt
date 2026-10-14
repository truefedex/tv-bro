package com.phlox.tvwebbrowser.browser

import android.util.Patterns
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.Locale

/**
 * Turns the text typed into the address bar into the url to load:
 * either the text itself (as a web address) or a search engine query.
 */
object UrlInputResolver {
    /**
     * @param searchEngineUrlTemplate search url where "[query]" is replaced with the encoded text
     * @return url to load or null if the text can not be encoded as a search query
     */
    fun resolve(input: String, searchEngineUrlTemplate: String): String? {
        var text = input
        val trimmedLowercased = text.trim { it <= ' ' }.lowercase(Locale.ROOT)
        if (Patterns.WEB_URL.matcher(text).matches() || trimmedLowercased.startsWith("http://") || trimmedLowercased.startsWith("https://")) {
            if (!text.lowercase(Locale.ROOT).contains("://")) {
                text = "https://$text"
            }
            return text
        }

        val query = try {
            URLEncoder.encode(text, "utf-8")
        } catch (e: UnsupportedEncodingException) {
            e.printStackTrace()
            return null
        }
        return searchEngineUrlTemplate.replace("[query]", query)
    }
}
