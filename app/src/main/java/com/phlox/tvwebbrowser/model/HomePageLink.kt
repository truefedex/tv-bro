package com.phlox.tvwebbrowser.model

import com.phlox.tvwebbrowser.TVBro
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class HomePageLink(
    val title: String,
    val url: String,
    val favicon: String? = null,
    val favoriteId: Long? = null,
    val order: Int? = null,
    val dest_url: String? = null,
    val description: String? = null,
    var validUntil: Date? = null
) {
    fun toJsonObj(): JSONObject {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return JSONObject().apply {
            put("title", title)
            put("url", url)
            put("favicon", favicon)
            put("favoriteId", favoriteId)
            put("order", order)
            put("dest_url", dest_url)
            put("description", description)
            put("validUntil", dateFormat.format(validUntil?: Date()))
        }
    }

    companion object {
        fun fromHistoryItem(item: HistoryItem): HomePageLink {
            return HomePageLink(item.title, item.url)
        }

        fun fromBookmarkItem(item: FavoriteItem): HomePageLink {
            return HomePageLink(item.title?: "", item.url?: "", item.favicon, item.id, item.order, item.destUrl, item.description, item.validUntil)
        }
    }
}