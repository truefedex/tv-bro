package com.phlox.tvwebbrowser.browser

import android.util.Log
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HomePageLink
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.data.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Links shown on the built-in home page: bookmarks, latest or most visited history items.
 */
class HomePageLinks(
    private val config: Config,
    private val history: HistoryRepository,
    private val favorites: FavoritesRepository
) {
    companion object {
        private const val TAG = "HomePageLinks"
    }

    private val _links = MutableStateFlow<List<HomePageLink>>(emptyList())
    /** Can be read from any thread (home page scripts ask for it on a background thread) */
    val links: StateFlow<List<HomePageLink>> = _links.asStateFlow()

    suspend fun load() {
        Log.d(TAG, "load")
        if (config.homePageMode != Config.HomePageMode.HOME_PAGE) return
        when (config.homePageLinksMode) {
            Config.HomePageLinksMode.MOST_VISITED -> {
                _links.value = history.mostVisited()
                    .map { HomePageLink.fromHistoryItem(it) }
            }
            Config.HomePageLinksMode.LATEST_HISTORY -> {
                _links.value = history.last(8)
                    .map { HomePageLink.fromHistoryItem(it) }
            }
            Config.HomePageLinksMode.BOOKMARKS -> {
                val bookmarks = favorites.homePageBookmarks()
                if (bookmarks.isEmpty() && !config.initialBookmarksSuggestionsLoaded) {
                    val suggestions = initialBookmarksSuggestions() ?: return
                    for (s in suggestions) {
                        favorites.save(s)
                    }
                    config.initialBookmarksSuggestionsLoaded = true
                    _links.value = suggestions.map { HomePageLink.fromBookmarkItem(it) }
                } else {
                    _links.value = bookmarks.map { HomePageLink.fromBookmarkItem(it) }
                }
            }
        }
    }

    suspend fun remove(link: HomePageLink) {
        _links.update { it - link }
        link.favoriteId?.let {
            favorites.delete(it)
        }
    }

    /**
     * Saves the bookmark edited (or created, if its id is 0) by the user and updates its link.
     */
    suspend fun onBookmarkEdited(item: FavoriteItem) {
        val isNew = item.id == 0L
        favorites.save(item)
        if (isNew) {
            _links.update { it + HomePageLink.fromBookmarkItem(item) }
        } else {
            _links.update { links ->
                links.map { if (it.favoriteId == item.id) HomePageLink.fromBookmarkItem(item) else it }
            }
        }
    }

    suspend fun markRecommendationAsUseful(bookmarkOrder: Int) {
        val link = _links.value.find { it.order == bookmarkOrder } ?: return
        val favoriteId = link.favoriteId ?: return
        if (link.validUntil == null) return
        favorites.markAsUseful(favoriteId)
    }

    private fun initialBookmarksSuggestions(): List<FavoriteItem>? {
        //There was loading of default bookmarks from app's home page (with affiliate links).
        // Now its just hardcoded. I left them as json array just for convenience to copy the same
        // data used in corresponding home html page where them useful for debug
        return try {
            val jsonArray = JSONArray("""[
        {"title": "Wikipedia - the free encyclopedia", "url":"https://www.wikipedia.org", "favicon": "https://ru.wikipedia.org/static/apple-touch/wikipedia.png"},
        {"title": "Reddit - Dive into anything", "url":"https://www.reddit.com", "favicon": "https://www.redditstatic.com/desktop2x/img/favicon/apple-icon-120x120.png"},
        {"title": "My Tuner", "url":"https://mytuner-radio.com/", "favicon": "https://static2.mytuner.mobi/static/icons/apple-touch-icon-120x120-precomposed.png"},
        {"title": "Instagram", "url":"https://www.instagram.com", "favicon": "https://static.cdninstagram.com/rsrc.php/v3/ys/r/aM-g435MtEX.png"},
        {"title": "IMDb", "url":"https://www.imdb.com/", "favicon": "https://m.media-amazon.com/images/G/01/imdb/images-ANDW73HA/favicon_iPhone_retina_180x180._CB1582158069_UX60_.png"},
        {"title": "eBay", "url":"https://www.ebay.com/", "favicon": "https://www.ebay.com/apple-touch-icon.png"},
        {"title": "Itch.io", "url":"https://itch.io/games/input-gamepad/platform-web", "favicon": "https://itch.io/static/images/itchio-square-144.png"},
        {"title": "BBC News", "url":"https://www.bbc.com/news", "favicon": "https://www.bbc.com/apple-touch-icon-120x120-precomposed.png"}
    ]""")
            val result = mutableListOf<FavoriteItem>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val favorite = FavoriteItem()
                favorite.title = jsonObject.getString("title")
                favorite.url = jsonObject.getString("url")
                favorite.favicon = jsonObject.opt("favicon") as String?
                favorite.destUrl = jsonObject.opt("dest_url") as String?
                favorite.description = jsonObject.opt("description") as String?
                favorite.order = i
                favorite.homePageBookmark = true
                val validUntil = jsonObject.opt("valid_until") as String?
                if (validUntil != null) {
                    favorite.validUntil = dateFormat.parse(validUntil)
                }
                result.add(favorite)
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
