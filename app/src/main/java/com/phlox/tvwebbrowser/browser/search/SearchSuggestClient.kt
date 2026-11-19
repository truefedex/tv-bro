package com.phlox.tvwebbrowser.browser.search

import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.utils.FaviconExtractor
import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * Asks a search engine for the suggestions of a text. The request is the app's own (through the proxy of
 * the app, [AppProxy.open]) and carries no cookies, so the engine can not tell whose it is.
 */
class SearchSuggestClient(
    private val open: (URL) -> URLConnection = { AppProxy.open(it) },
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
    private val suggestLang: () -> String = {
        SearchEngines.suggestLang(Locale.getDefault(), RegionDetector.country(AppContext.get()))
    }
) {
    /**
     * The suggestions of the engine at [template] (with [SearchEngines.QUERY]) for the [query]; blocks
     * @throws SearchSuggestRefusedException when the engine refuses the request (429, 403)
     * @throws IOException when the engine can not be reached or does not answer with 200
     */
    fun fetch(template: String, query: String): List<String> {
        if (!SearchEngines.isSuggestUrl(template)) throw IOException("Not an address of suggestions")
        var filled = template.replace(SearchEngines.QUERY, URLEncoder.encode(query, "UTF-8"))
        if (filled.contains(Config.SUGGEST_LANG)) {
            filled = filled.replace(Config.SUGGEST_LANG, URLEncoder.encode(suggestLang(), "UTF-8"))
        }
        val url = URL(filled)
        val connection = open(url)
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", FaviconExtractor.USER_AGENT)
            connection.setRequestProperty("Accept", "application/x-suggestions+json,application/json,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", language())
            if (connection is HttpURLConnection && connection.responseCode != HttpURLConnection.HTTP_OK) {
                val code = connection.responseCode
                if (code == 429 || code == HttpURLConnection.HTTP_FORBIDDEN) throw SearchSuggestRefusedException(code)
                throw IOException("HTTP $code")
            }
            val body = connection.getInputStream().use { readLimited(it) }
            return SearchSuggestParser.parse(String(body, charsetOf(connection.contentType)))
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY_BYTES) throw IOException("The answer is too long")
        }
        return out.toByteArray()
    }

    companion object {
        const val TIMEOUT_MS = 3_000
        const val MAX_BODY_BYTES = 64 * 1024

        /** The charset a Content-Type tells, UTF-8 when it tells none (or one that is not known) */
        fun charsetOf(contentType: String?): Charset {
            val name = contentType?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substring("charset=".length)?.trim('"', '\'', ' ')
            return try {
                if (name.isNullOrEmpty()) Charsets.UTF_8 else Charset.forName(name)
            } catch (e: Exception) {
                Charsets.UTF_8
            }
        }
    }
}

/** The engine refused to suggest: too many requests (429), or not allowed (403) */
class SearchSuggestRefusedException(val code: Int) : IOException("HTTP $code")

/** Reads OpenSearch suggestions: `["text", ["suggestion", …], …]` */
object SearchSuggestParser {
    const val MAX_SUGGESTIONS = 20
    const val MAX_SUGGESTION_LENGTH = 200

    /** The suggestions of the [body], none when it is something else */
    fun parse(body: String): List<String> {
        val items = try {
            JSONArray(body).optJSONArray(1) ?: return emptyList()
        } catch (e: JSONException) {
            return emptyList()
        }
        val suggestions = ArrayList<String>()
        for (i in 0 until items.length()) {
            val text = (items.opt(i) as? String)?.trim() ?: continue
            if (text.isEmpty() || text.length > MAX_SUGGESTION_LENGTH) continue
            suggestions += text
            if (suggestions.size == MAX_SUGGESTIONS) break
        }
        return suggestions
    }
}
