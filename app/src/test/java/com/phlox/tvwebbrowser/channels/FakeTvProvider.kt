package com.phlox.tvwebbrowser.channels

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.PreviewPrograms
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import java.io.File

/**
 * The TvProvider of the system as far as the channels of one app need it: no selection of the caller (the real
 * one refuses them), programs of a channel by its query parameter, a channel's programs deleted with it.
 */
class FakeTvProvider : ContentProvider() {
    private var nextId = 1L
    val channels = LinkedHashMap<Long, ContentValues>()
    val programs = LinkedHashMap<Long, ContentValues>()
    var inserts = 0
    var updates = 0
    var deletes = 0
    var logoWrites = 0

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = null

    private fun isChannel(uri: Uri) = uri.pathSegments.firstOrNull() == Channels.CONTENT_URI.lastPathSegment

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        inserts++
        val id = nextId++
        val row = ContentValues(values).apply { put(BaseColumns._ID, id) }
        return when (uri) {
            Channels.CONTENT_URI -> channels.put(id, row).let { TvContract.buildChannelUri(id) }
            PreviewPrograms.CONTENT_URI -> programs.put(id, row).let { TvContract.buildPreviewProgramUri(id) }
            else -> null
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        require(selection == null && selectionArgs == null)
        updates++
        val table = if (isChannel(uri)) channels else programs
        val row = table[ContentUris.parseId(uri)] ?: return 0
        row.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        require(selection == null && selectionArgs == null)
        deletes++
        val id = ContentUris.parseId(uri)
        if (isChannel(uri)) {
            programs.values.removeAll { it.getAsLong(PreviewPrograms.COLUMN_CHANNEL_ID) == id }
            return if (channels.remove(id) != null) 1 else 0
        }
        return if (programs.remove(id) != null) 1 else 0
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(selection == null && selectionArgs == null)
        val columns = projection!!
        val cursor = MatrixCursor(columns)
        val rows = when {
            uri == Channels.CONTENT_URI -> channels.values.toList()
            isChannel(uri) -> listOfNotNull(channels[ContentUris.parseId(uri)])
            else -> {
                val channelId = uri.getQueryParameter("channel")?.toLong()
                programs.values.filter { channelId == null || it.getAsLong(PreviewPrograms.COLUMN_CHANNEL_ID) == channelId }
            }
        }
        for (row in rows) cursor.addRow(columns.map { row.get(it) }.toTypedArray())
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        logoWrites++
        val file = File.createTempFile("logo", ".png").apply { deleteOnExit() }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
    }

    fun channelsOf(providerId: String) = channels.values.filter { it.getAsString(Channels.COLUMN_INTERNAL_PROVIDER_ID) == providerId }

    fun programsOf(channelId: Long) = programs.values.filter { it.getAsLong(PreviewPrograms.COLUMN_CHANNEL_ID) == channelId }
        .sortedByDescending { it.getAsLong(PreviewPrograms.COLUMN_WEIGHT) }

    fun resetCounts() {
        inserts = 0; updates = 0; deletes = 0
    }
}
