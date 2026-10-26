package com.phlox.tvwebbrowser.activity.main

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.PreviewPrograms
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.FavoriteItem

/** Publishes the bookmarks as a preview channel on the Android TV home screen */
object HomeScreenChannel {
    private val TAG = HomeScreenChannel::class.java.simpleName
    private const val CHANNEL_PROVIDER_ID = "bookmarks"
    private const val LOGO_SIZE_PX = 80

    fun sync(context: Context, config: Config, bookmarks: List<FavoriteItem>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!config.homeScreenChannel && config.homeScreenChannelId == -1L) return
        try {
            syncImpl(context, config, bookmarks)
        } catch (e: Exception) {
            Log.w(TAG, "sync", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun syncImpl(context: Context, config: Config, bookmarks: List<FavoriteItem>) {
        val resolver = context.contentResolver
        if (!config.homeScreenChannel) {
            val id = findChannelId(resolver, config.homeScreenChannelId) ?: config.homeScreenChannelId
            if (id != -1L) resolver.delete(TvContract.buildChannelUri(id), null, null)
            config.homeScreenChannelId = -1L
            return
        }
        val channelId = getOrCreateChannel(context, resolver, config) ?: return
        val existing = queryPrograms(resolver, channelId)
        val items = bookmarks.filter { !it.url.isNullOrBlank() }
        for ((index, item) in items.withIndex()) {
            val url = item.url!!
            val values = programValues(context, channelId, item, url, items.size - index)
            val programId = existing.remove(url)
            if (programId != null) {
                resolver.update(TvContract.buildPreviewProgramUri(programId), values, null, null)
            } else {
                resolver.insert(PreviewPrograms.CONTENT_URI, values)
            }
        }
        for (programId in existing.values) {
            resolver.delete(TvContract.buildPreviewProgramUri(programId), null, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getOrCreateChannel(context: Context, resolver: ContentResolver, config: Config): Long? {
        findChannelId(resolver, config.homeScreenChannelId)?.let { id ->
            if (config.homeScreenChannelId != id) config.homeScreenChannelId = id
            return id
        }
        val values = ContentValues().apply {
            put(Channels.COLUMN_TYPE, Channels.TYPE_PREVIEW)
            put(Channels.COLUMN_DISPLAY_NAME, context.getString(R.string.bookmarks))
            put(Channels.COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_PROVIDER_ID)
            put(Channels.COLUMN_APP_LINK_INTENT_URI, Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
        }
        val channelId = ContentUris.parseId(resolver.insert(Channels.CONTENT_URI, values) ?: return null)
        config.homeScreenChannelId = channelId
        AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)?.let { drawable ->
            val bitmap = Bitmap.createBitmap(LOGO_SIZE_PX, LOGO_SIZE_PX, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, LOGO_SIZE_PX, LOGO_SIZE_PX)
            drawable.draw(Canvas(bitmap))
            resolver.openOutputStream(TvContract.buildChannelLogoUri(channelId))?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        TvContract.requestChannelBrowsable(context, channelId)
        return channelId
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun findChannelId(resolver: ContentResolver, savedId: Long): Long? {
        // TvProvider rejects caller selection clauses and scopes queries to the calling package
        resolver.query(Channels.CONTENT_URI, arrayOf(BaseColumns._ID, Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            var fallback: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id == savedId) return id
                if (c.getString(1) == CHANNEL_PROVIDER_ID) fallback = id
            }
            return fallback
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun queryPrograms(resolver: ContentResolver, channelId: Long): MutableMap<String, Long> {
        val map = LinkedHashMap<String, Long>()
        val uri = TvContract.buildPreviewProgramsUriForChannel(channelId)
        resolver.query(uri, arrayOf(BaseColumns._ID, PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val key = c.getString(1)
                if (key != null && map.putIfAbsent(key, id) == null) continue
                resolver.delete(TvContract.buildPreviewProgramUri(id), null, null)
            }
        }
        return map
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun programValues(context: Context, channelId: Long, item: FavoriteItem, url: String, weight: Int): ContentValues {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        val posterUri = when {
            !item.favicon.isNullOrBlank() -> item.favicon!!
            (scheme == "http" || scheme == "https") && !uri.authority.isNullOrEmpty() -> "$scheme://${uri.authority}/favicon.ico"
            else -> "android.resource://${context.packageName}/${R.mipmap.ic_launcher}"
        }
        return ContentValues().apply {
            put(PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
            put(PreviewPrograms.COLUMN_TYPE, PreviewPrograms.TYPE_CLIP)
            put(PreviewPrograms.COLUMN_TITLE, item.title?.ifBlank { null } ?: url)
            put(PreviewPrograms.COLUMN_SHORT_DESCRIPTION, url)
            put(PreviewPrograms.COLUMN_POSTER_ART_URI, posterUri)
            put(PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, PreviewPrograms.ASPECT_RATIO_1_1)
            put(PreviewPrograms.COLUMN_INTENT_URI, Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
            put(PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, url)
            put(PreviewPrograms.COLUMN_WEIGHT, weight)
        }
    }
}
