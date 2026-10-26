package com.phlox.tvwebbrowser.activity.main

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.PreviewPrograms
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.model.FavoriteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HomeScreenChannelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val config get() = AppContext.provideConfig()
    private lateinit var provider: FakeTvProvider

    class FakeTvProvider : ContentProvider() {
        private var nextId = 1L
        val channels = LinkedHashMap<Long, ContentValues>()
        val programs = LinkedHashMap<Long, ContentValues>()
        var logoWrites = 0

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val id = nextId++
            val row = ContentValues(values).apply { put(BaseColumns._ID, id) }
            return when (uri) {
                Channels.CONTENT_URI -> channels.put(id, row).let { TvContract.buildChannelUri(id) }
                PreviewPrograms.CONTENT_URI -> programs.put(id, row).let { TvContract.buildPreviewProgramUri(id) }
                else -> null
            }
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            // TvProvider rejects caller selection clauses
            require(selection == null && selectionArgs == null)
            val table = if (uri.pathSegments.firstOrNull() == "channel") channels else programs
            val row = table[ContentUris.parseId(uri)] ?: return 0
            row.putAll(values)
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            require(selection == null && selectionArgs == null)
            val id = ContentUris.parseId(uri)
            return if (uri.pathSegments.firstOrNull() == "channel") {
                programs.entries.removeIf { it.value.getAsLong(PreviewPrograms.COLUMN_CHANNEL_ID) == id }
                if (channels.remove(id) != null) 1 else 0
            } else {
                if (programs.remove(id) != null) 1 else 0
            }
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor {
            require(selection == null && selectionArgs == null && sortOrder == null)
            val rows = when (uri.path) {
                Channels.CONTENT_URI.path -> channels.values.toList()
                PreviewPrograms.CONTENT_URI.path -> {
                    val channelId = uri.getQueryParameter("channel")?.toLongOrNull()
                    programs.values.filter { channelId == null || it.getAsLong(PreviewPrograms.COLUMN_CHANNEL_ID) == channelId }
                }
                else -> emptyList()
            }
            val cols = projection ?: arrayOf(BaseColumns._ID)
            return MatrixCursor(cols).apply { rows.forEach { row -> addRow(cols.map { row.get(it) }) } }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            logoWrites++
            val tmp = File.createTempFile("logo", ".png").apply { deleteOnExit() }
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE)
        }
    }

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeTvProvider::class.java, TvContract.AUTHORITY)
    }

    private fun bookmark(id: Long, title: String, url: String = "https://$title.com/", favicon: String? = null) =
        FavoriteItem().apply { this.id = id; this.title = title; this.url = url; this.favicon = favicon }

    private fun orderedPrograms(): List<ContentValues> =
        provider.programs.values.sortedByDescending { it.getAsInteger(PreviewPrograms.COLUMN_WEIGHT) }

    @Test
    fun publishesUpdatesAndRemovesChannelAndPrograms() {
        HomeScreenChannel.sync(context, config, listOf(bookmark(1, "a")))
        assertTrue(provider.channels.isEmpty())

        config.homeScreenChannel = true
        HomeScreenChannel.sync(context, config, listOf(
            bookmark(1, "Alpha", "https://alpha.example/page", "https://alpha.example/icon.png"),
            bookmark(2, "Beta", "https://beta.example/")
        ))
        assertEquals(1, provider.channels.size)
        assertEquals(1, provider.logoWrites)
        val programs = orderedPrograms()
        assertEquals(listOf("Alpha", "Beta"), programs.map { it.getAsString(PreviewPrograms.COLUMN_TITLE) })
        assertEquals(listOf(2, 1), programs.map { it.getAsInteger(PreviewPrograms.COLUMN_WEIGHT) })
        assertEquals("https://alpha.example/icon.png", programs[0].getAsString(PreviewPrograms.COLUMN_POSTER_ART_URI))
        assertEquals("https://beta.example/favicon.ico", programs[1].getAsString(PreviewPrograms.COLUMN_POSTER_ART_URI))
        val intent = Intent.parseUri(programs[0].getAsString(PreviewPrograms.COLUMN_INTENT_URI), Intent.URI_INTENT_SCHEME)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://alpha.example/page"), intent.data)
        assertEquals(MainActivity::class.java.name, intent.component?.className)

        // Rename, reorder, add and remove update existing rows in place without recreating the channel
        val idOfAlpha = provider.programs.entries.first { it.value.getAsString(PreviewPrograms.COLUMN_TITLE) == "Alpha" }.key
        HomeScreenChannel.sync(context, config, listOf(bookmark(3, "Gamma"), bookmark(1, "Alpha 2", "https://alpha.example/page")))
        assertEquals(1, provider.channels.size)
        assertEquals(1, provider.logoWrites)
        assertEquals(listOf("Gamma", "Alpha 2"), orderedPrograms().map { it.getAsString(PreviewPrograms.COLUMN_TITLE) })
        assertEquals("Alpha 2", provider.programs[idOfAlpha]?.getAsString(PreviewPrograms.COLUMN_TITLE))

        config.homeScreenChannel = false
        HomeScreenChannel.sync(context, config, listOf(bookmark(3, "Gamma")))
        assertTrue(provider.channels.isEmpty())
        assertTrue(provider.programs.isEmpty())
        assertEquals(-1L, config.homeScreenChannelId)
    }
}
