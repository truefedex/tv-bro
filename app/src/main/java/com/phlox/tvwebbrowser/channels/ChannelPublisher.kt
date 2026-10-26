package com.phlox.tvwebbrowser.channels

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.PreviewPrograms
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.phlox.tvwebbrowser.R

/** A channel of the home screen; [id] is its `COLUMN_INTERNAL_PROVIDER_ID` and the folder of its posters */
enum class ChannelKind(val id: String, val title: Int, val aspectRatio: Int, val limit: Int) {
    BOOKMARKS("bookmarks", R.string.bookmarks, PreviewPrograms.ASPECT_RATIO_1_1, 40),
    TABS("tabs", R.string.tabs, PreviewPrograms.ASPECT_RATIO_16_9, 30),
    DOWNLOADS("downloads", R.string.downloads, PreviewPrograms.ASPECT_RATIO_16_9, 20)
}

/**
 * A program (a tile) of a channel; [key] tells it apart from the other programs of its channel. The launcher
 * puts heavier programs first: [weight] is given where it does not change when others come (downloads, whose
 * new ones come first), else it follows the place in the list.
 */
data class ChannelProgram(
    val key: String,
    val title: String,
    val description: String?,
    val posterUri: String?,
    val intentUri: String,
    val weight: Long? = null
)

/**
 * The work with the TvProvider of the system: the channels of the app are found by their internal id (TvProvider
 * gives an app only its own channels and takes no selection of the caller), and the programs of a channel are
 * written only where they differ, so the launcher redraws only what changed.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ChannelPublisher(private val resolver: ContentResolver) {
    data class Channel(val id: Long, val name: String?, val browsable: Boolean)

    /** What a publication changed, for tests and logs */
    data class Changes(val inserted: Int, val updated: Int, val deleted: Int)

    /** The channel of the kind; the duplicates a crash or an old version left are deleted */
    fun find(kind: ChannelKind): Channel? {
        val found = channels().filter { it.second == kind.id }.map { it.first }.sortedBy { it.id }
        for (duplicate in found.drop(1)) {
            Log.w(TAG, "find: deleting a duplicate channel ${duplicate.id} of ${kind.id}")
            resolver.delete(TvContract.buildChannelUri(duplicate.id), null, null)
        }
        return found.firstOrNull()
    }

    fun create(kind: ChannelKind, name: String, appLinkIntentUri: String, logo: Bitmap?): Long? {
        val values = ContentValues().apply {
            put(Channels.COLUMN_TYPE, Channels.TYPE_PREVIEW)
            put(Channels.COLUMN_DISPLAY_NAME, name)
            put(Channels.COLUMN_INTERNAL_PROVIDER_ID, kind.id)
            put(Channels.COLUMN_APP_LINK_INTENT_URI, appLinkIntentUri)
        }
        val uri = resolver.insert(Channels.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        if (logo != null) {
            try {
                resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use {
                    logo.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "create: no logo for ${kind.id}", e)
            }
        }
        return id
    }

    /** The name and the link of the channel follow the language and the version of the app */
    fun updateChannel(channel: Channel, name: String, appLinkIntentUri: String) {
        val current = channelValues(channel.id) ?: return
        if (current.first == name && current.second == appLinkIntentUri) return
        resolver.update(TvContract.buildChannelUri(channel.id), ContentValues().apply {
            put(Channels.COLUMN_DISPLAY_NAME, name)
            put(Channels.COLUMN_APP_LINK_INTENT_URI, appLinkIntentUri)
        }, null, null)
    }

    /** Deletes every channel of the kind (their programs go with them); the number deleted */
    fun delete(kind: ChannelKind): Int {
        var deleted = 0
        for ((channel, providerId) in channels()) {
            if (providerId != kind.id) continue
            deleted += resolver.delete(TvContract.buildChannelUri(channel.id), null, null)
        }
        return deleted
    }

    /** Makes the programs of the channel [programs], in their order (the first is the leftmost) */
    fun publish(channelId: Long, aspectRatio: Int, programs: List<ChannelProgram>): Changes {
        val existing = HashMap<String, ExistingProgram>()
        var deleted = 0
        resolver.query(TvContract.buildPreviewProgramsUriForChannel(channelId), PROGRAM_COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val program = ExistingProgram(c.getLong(0), ContentValues().apply {
                    for (i in 2 until PROGRAM_COLUMNS.size) {
                        if (c.isNull(i)) putNull(PROGRAM_COLUMNS[i])
                        else if (c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER) put(PROGRAM_COLUMNS[i], c.getLong(i))
                        else put(PROGRAM_COLUMNS[i], c.getString(i))
                    }
                })
                val key = c.getString(1)
                if (key == null || existing.containsKey(key)) {
                    deleted += resolver.delete(TvContract.buildPreviewProgramUri(program.id), null, null)
                } else {
                    existing[key] = program
                }
            }
        }
        var inserted = 0
        var updated = 0
        val unique = programs.distinctBy { it.key }
        for ((index, program) in unique.withIndex()) {
            //by place, not by count: a program added at the end changes no other
            val values = programValues(program, aspectRatio, program.weight ?: (WEIGHT_FIRST - index).toLong())
            val old = existing.remove(program.key)
            if (old == null) {
                values.put(PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
                values.put(PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, program.key)
                if (resolver.insert(PreviewPrograms.CONTENT_URI, values) != null) inserted++
            } else if (!sameValues(old.values, values)) {
                updated += resolver.update(TvContract.buildPreviewProgramUri(old.id), values, null, null)
            }
        }
        for (stale in existing.values) {
            deleted += resolver.delete(TvContract.buildPreviewProgramUri(stale.id), null, null)
        }
        return Changes(inserted, updated, deleted)
    }

    private class ExistingProgram(val id: Long, val values: ContentValues)

    private fun programValues(program: ChannelProgram, aspectRatio: Int, weight: Long) = ContentValues().apply {
        put(PreviewPrograms.COLUMN_TYPE, PreviewPrograms.TYPE_CLIP.toLong())
        put(PreviewPrograms.COLUMN_TITLE, program.title)
        put(PreviewPrograms.COLUMN_SHORT_DESCRIPTION, program.description)
        put(PreviewPrograms.COLUMN_POSTER_ART_URI, program.posterUri)
        put(PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, aspectRatio.toLong())
        put(PreviewPrograms.COLUMN_INTENT_URI, program.intentUri)
        put(PreviewPrograms.COLUMN_WEIGHT, weight)
    }

    private fun sameValues(old: ContentValues, new: ContentValues): Boolean =
        new.keySet().all { key -> old.get(key)?.toString() == new.get(key)?.toString() }

    /** The channels of the app with their internal ids */
    private fun channels(): List<Pair<Channel, String?>> {
        val result = ArrayList<Pair<Channel, String?>>()
        resolver.query(Channels.CONTENT_URI, arrayOf(BaseColumns._ID, Channels.COLUMN_INTERNAL_PROVIDER_ID,
            Channels.COLUMN_DISPLAY_NAME, Channels.COLUMN_BROWSABLE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result += Channel(c.getLong(0), c.getString(2), !c.isNull(3) && c.getInt(3) != 0) to c.getString(1)
            }
        }
        return result
    }

    private fun channelValues(id: Long): Pair<String?, String?>? =
        resolver.query(TvContract.buildChannelUri(id), arrayOf(Channels.COLUMN_DISPLAY_NAME,
            Channels.COLUMN_APP_LINK_INTENT_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }

    companion object {
        private const val TAG = "ChannelPublisher"
        /** The weight of the leftmost program; the launcher shows heavier ones first */
        private const val WEIGHT_FIRST = 1_000_000

        /** The id and the key first, then the columns [programValues] writes */
        private val PROGRAM_COLUMNS = arrayOf(BaseColumns._ID, PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            PreviewPrograms.COLUMN_TYPE, PreviewPrograms.COLUMN_TITLE, PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            PreviewPrograms.COLUMN_POSTER_ART_URI, PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            PreviewPrograms.COLUMN_INTENT_URI, PreviewPrograms.COLUMN_WEIGHT)
    }
}
