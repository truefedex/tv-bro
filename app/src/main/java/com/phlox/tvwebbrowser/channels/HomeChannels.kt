package com.phlox.tvwebbrowser.channels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.TVBro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * The channels of the app on the Android TV home screen (Android 8+, preview channels of TvProvider): the
 * bookmarks, the open tabs and the finished downloads, each turned on in the settings.
 *
 * A sync makes the channels of the given kinds what the settings say: an enabled one is created when missing
 * and gets its programs, a disabled one is deleted (also when the app lost its prefs: channels are found by
 * their internal id, nothing is kept about them). Syncs run one at a time, also across the processes of the
 * app (a lock file), so two of them never create a channel twice.
 */
object HomeChannels {
    private const val TAG = "HomeChannels"
    private const val LOGO_SIDE = 160
    private val mutex = Mutex()

    enum class Mode {
        /** Creates and deletes channels as the settings say (the browser processes) */
        FULL,
        /**
         * Only refreshes the programs of channels that exist (the downloads process, whose prefs may be stale:
         * it never brings back a channel the user turned off)
         */
        UPDATE_EXISTING
    }

    val defaultSourcesFactory: (Context) -> Map<ChannelKind, ChannelSource> = ::defaultSources

    /** Tests give their own sources */
    @VisibleForTesting
    var sourcesFactory: (Context) -> Map<ChannelKind, ChannelSource> = defaultSourcesFactory

    /** Robolectric knows no TvProvider package */
    @VisibleForTesting
    var supportedForTests: Boolean? = null

    /** The device has a home screen with channels (Android 8+ with the TvProvider of Android TV) */
    fun isSupported(context: Context): Boolean {
        supportedForTests?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.packageManager.resolveContentProvider(TvContract.AUTHORITY, 0) != null
        } catch (e: Exception) {
            false
        }
    }

    fun isEnabled(config: Config, kind: ChannelKind): Boolean = when (kind) {
        ChannelKind.BOOKMARKS -> config.channelBookmarks
        ChannelKind.TABS -> config.channelTabs
        ChannelKind.DOWNLOADS -> config.channelDownloads
    }

    /**
     * Makes the channels of the [kinds] what the settings say. Never throws.
     * @return the ids of the channels that are there after it
     */
    suspend fun sync(context: Context, kinds: Collection<ChannelKind> = ChannelKind.entries,
                     mode: Mode = Mode.FULL): Map<ChannelKind, Long> {
        if (!isSupported(context)) return emptyMap()
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    withProcessLock(appContext) { syncLocked(appContext, kinds, mode) }
                } catch (e: Exception) {
                    Log.e(TAG, "sync of $kinds failed", e)
                    emptyMap()
                }
            }
        }
    }

    /** The user let the channel show on the home screen; null if there is no such channel */
    suspend fun isBrowsable(context: Context, kind: ChannelKind): Boolean? {
        if (!isSupported(context)) return null
        return withContext(Dispatchers.IO) {
            try {
                ChannelPublisher(context.contentResolver).find(kind)?.browsable
            } catch (e: Exception) {
                Log.e(TAG, "isBrowsable", e)
                null
            }
        }
    }

    private suspend fun syncLocked(context: Context, kinds: Collection<ChannelKind>, mode: Mode): Map<ChannelKind, Long> {
        val publisher = ChannelPublisher(context.contentResolver)
        val config = AppContext.provideConfig()
        val sources by lazy { sourcesFactory(context) }
        val result = HashMap<ChannelKind, Long>()
        for (kind in kinds) {
            try {
                if (mode == Mode.FULL && !isEnabled(config, kind)) {
                    if (publisher.delete(kind) > 0) Log.i(TAG, "deleted the channel of ${kind.id}")
                    ChannelPosters.cleanup(context, kind, emptyList())
                    continue
                }
                val source = sources[kind] ?: continue
                val name = context.getString(kind.title)
                val appLink = source.appLink(context).toUri(android.content.Intent.URI_INTENT_SCHEME)
                val existing = publisher.find(kind)
                val channelId = when {
                    existing != null -> existing.id.also { publisher.updateChannel(existing, name, appLink) }
                    mode == Mode.UPDATE_EXISTING -> continue
                    else -> publisher.create(kind, name, appLink, logo(context))?.also { id ->
                        Log.i(TAG, "created the channel of ${kind.id}: $id")
                        try {
                            TvContract.requestChannelBrowsable(context, id)
                        } catch (e: Exception) {
                            Log.w(TAG, "requestChannelBrowsable", e)
                        }
                    } ?: continue
                }
                val programs = source.programs(context).take(kind.limit)
                val changes = publisher.publish(channelId, kind.aspectRatio, programs)
                Log.i(TAG, "published ${kind.id}: ${programs.size} programs, $changes")
                ChannelPosters.cleanup(context, kind, programs.map { it.posterUri })
                result[kind] = channelId
            } catch (e: Exception) {
                Log.e(TAG, "sync of ${kind.id} failed", e)
            }
        }
        return result
    }

    /** One sync at a time in all processes of the app (the mutex covers the threads of this one) */
    private inline fun <T> withProcessLock(context: Context, block: () -> T): T {
        RandomAccessFile(File(context.noBackupFilesDir, "channels.lock"), "rw").use { file ->
            val lock = file.channel.lock()
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }

    private fun logo(context: Context): Bitmap? = try {
        val icon = context.packageManager.getApplicationIcon(context.applicationInfo)
        Bitmap.createBitmap(LOGO_SIDE, LOGO_SIDE, Bitmap.Config.ARGB_8888).also {
            icon.setBounds(0, 0, LOGO_SIDE, LOGO_SIDE)
            icon.draw(Canvas(it))
        }
    } catch (e: Exception) {
        null
    }

    private fun defaultSources(context: Context): Map<ChannelKind, ChannelSource> {
        val container = TVBro.container
        val data = container.data
        val config = AppContext.provideConfig()
        val favicons = CachedFavicons(data.hostConfigs)
        return mapOf(
            ChannelKind.BOOKMARKS to BookmarksChannelSource(data.favorites, favicons, config),
            ChannelKind.TABS to TabsChannelSource(data.tabs, container.tabFiles, favicons, config),
            ChannelKind.DOWNLOADS to DownloadsChannelSource(data.downloads)
        )
    }
}
