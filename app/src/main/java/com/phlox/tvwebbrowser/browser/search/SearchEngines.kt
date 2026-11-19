package com.phlox.tvwebbrowser.browser.search

import com.phlox.tvwebbrowser.Config

/** What the app knows about the search engines of [Config]: where they suggest searches */
object SearchEngines {
    const val QUERY = "[query]"

    /** A label of the host of each engine of [Config.SearchEnginesURLs] (its sites in all countries) */
    private val HOST_LABELS = listOf(
        setOf("google"), setOf("bing"), setOf("yahoo"), setOf("duckduckgo"), setOf("yandex", "ya"), setOf("startpage"),
        setOf("qwant")
    )

    /**
     * The language and the country of the user for the suggestions of Qwant ([Config.SUGGEST_LANG]): as fr_fr,
     * the country of the device when the language has none, en_gb when neither is known (Qwant answers pairs
     * it does not know, en_de, in English)
     */
    fun suggestLang(locale: java.util.Locale, country: String?): String {
        val language = locale.language.lowercase().takeIf { it.length in 2..3 } ?: return "en_gb"
        val region = locale.country.ifEmpty { country.orEmpty() }.lowercase()
        if (region.length != 2 || !region.all { it in 'a'..'z' }) return "en_gb"
        return "${language}_$region"
    }

    /**
     * The address of the suggestions (with [QUERY]) of the search engine of [searchUrl], null when it has
     * none: that of an engine of the list; for the user's own engine [customSuggestUrl], else that of the
     * engine of the list its host belongs to (google.com.ua).
     */
    fun suggestUrlOf(searchUrl: String, customSuggestUrl: String): String? {
        val index = Config.SearchEnginesURLs.indexOf(searchUrl)
        if (index >= 0 && Config.SearchEnginesSuggestURLs[index].isNotEmpty()) {
            return Config.SearchEnginesSuggestURLs[index]
        }
        val custom = customSuggestUrl.trim()
        if (custom.isNotEmpty()) return custom.takeIf { isSuggestUrl(it) }
        val host = hostOf(searchUrl) ?: return null
        val labels = host.split('.')
        val known = HOST_LABELS.indexOfFirst { names -> labels.any { it in names } }
        return if (known >= 0) Config.SearchEnginesSuggestURLs[known] else null
    }

    /** An http or https address with the [QUERY] in it */
    fun isSuggestUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return (scheme == "http" || scheme == "https") && url.contains(QUERY) && hostOf(url) != null
    }

    /** The host of the [url] in lower case, null when it has none */
    fun hostOf(url: String): String? = Config.searchEngineHost(url)
}
