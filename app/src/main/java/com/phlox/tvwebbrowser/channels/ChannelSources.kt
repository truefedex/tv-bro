package com.phlox.tvwebbrowser.channels

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import android.util.Size
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.view.icon
import com.phlox.tvwebbrowser.browser.DownloadItem
import com.phlox.tvwebbrowser.browser.TabFilesStore
import com.phlox.tvwebbrowser.data.DownloadsRepository
import com.phlox.tvwebbrowser.data.FavoritesRepository
import com.phlox.tvwebbrowser.data.HostConfigRepository
import com.phlox.tvwebbrowser.data.TabsRepository
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.DownloadFiles
import java.io.File
import java.text.DateFormat
import java.util.Date

/** What a channel shows: its programs, made when the channel is published */
interface ChannelSource {
    /** Where the channel itself leads (its name and logo on the home screen) */
    fun appLink(context: Context): Intent

    /** The programs, the first one leftmost; posters are drawn here (blocking, on a background thread) */
    suspend fun programs(context: Context): List<ChannelProgram>
}

/** Favicons the app has saved already (the launcher gets no network requests, the sync makes none either) */
class CachedFavicons(private val hostConfigs: HostConfigRepository) {
    /** The name of the saved favicon file of the host, null if there is none */
    suspend fun fileName(host: String): String? =
        hostConfigs.find(host)?.favicon?.takeIf { File(FaviconsPool.favIconsDir(), it).isFile }

    fun load(fileName: String?): Bitmap? =
        fileName?.let { BitmapFactory.decodeFile(File(FaviconsPool.favIconsDir(), it).absolutePath) }
}

private fun hostOf(url: String): String = Uri.parse(url).host ?: url

class BookmarksChannelSource(
    private val favorites: FavoritesRepository,
    private val favicons: CachedFavicons,
    private val config: Config
) : ChannelSource {
    override fun appLink(context: Context) = Intent(context, MainActivity::class.java)

    override suspend fun programs(context: Context): List<ChannelProgram> {
        val bookmarks = favorites.channelBookmarks(config.channelBookmarksSource, config.channelBookmarksFolder)
        return bookmarks.take(ChannelKind.BOOKMARKS.limit).map { item ->
            val url = item.url!!
            val host = hostOf(url)
            val title = item.title?.takeIf { it.isNotBlank() } ?: url
            val favicon = favicons.fileName(host)
            val poster = ChannelPosters.poster(context, ChannelKind.BOOKMARKS, ChannelPosters.key(host, favicon)) {
                ChannelPosters.siteTile(context, host, favicons.load(favicon))
            }
            ChannelProgram("b:$url", title, url, poster,
                MainActivity.channelBookmarkIntent(context, url).toUri(Intent.URI_INTENT_SCHEME))
        }
    }
}

class TabsChannelSource(
    private val tabs: TabsRepository,
    private val tabFiles: TabFilesStore,
    private val favicons: CachedFavicons,
    private val config: Config
) : ChannelSource {
    override fun appLink(context: Context) = Intent(context, MainActivity::class.java)

    override suspend fun programs(context: Context): List<ChannelProgram> {
        //the normal tabs can not be reached from incognito mode, and leaving it closes the incognito tabs
        if (config.incognitoMode) return emptyList()
        return tabs.load(incognito = false).sortedBy { it.position }.take(ChannelKind.TABS.limit).map { tab ->
            val url = tab.url.ifBlank { "about:blank" }
            val host = hostOf(url)
            val thumbnail = tab.thumbnailHash?.let { tabFiles.existingThumbnailFile(it) }
            val poster = if (thumbnail != null) {
                ChannelPosters.poster(context, ChannelKind.TABS, ChannelPosters.key("thumb", thumbnail.name)) {
                    BitmapFactory.decodeFile(thumbnail.absolutePath)?.let { ChannelPosters.wideCrop(it) }
                }
            } else null
            val favicon = if (poster == null) favicons.fileName(host) else null
            ChannelProgram("t:${tab.id}", tab.title.ifBlank { url }, url,
                poster ?: ChannelPosters.poster(context, ChannelKind.TABS, ChannelPosters.key("site", host, favicon)) {
                    ChannelPosters.wideSiteTile(context, host, favicons.load(favicon))
                },
                MainActivity.channelTabIntent(context, tab.id, url).toUri(Intent.URI_INTENT_SCHEME))
        }
    }
}

class DownloadsChannelSource(private val downloads: DownloadsRepository) : ChannelSource {
    override fun appLink(context: Context) = MainActivity.showDownloadsIntent(context)

    override suspend fun programs(context: Context): List<ChannelProgram> {
        val done = downloads.withStatus(Download.Status.DONE).sortedByDescending { it.id }
        val result = ArrayList<ChannelProgram>()
        for (download in done) {
            if (result.size >= ChannelKind.DOWNLOADS.limit) break
            if (!DownloadFiles.exists(context, download.filepath)) continue
            val item = DownloadItem.of(download)
            val kind = DownloadsNavigator.kindOf(item)
            val poster = ChannelPosters.poster(context, ChannelKind.DOWNLOADS,
                ChannelPosters.key(download.id, download.filepath, download.size)) {
                preview(context, download, kind)?.let { ChannelPosters.wideCover(it) }
                    ?: ChannelPosters.fileTile(context, kind.icon, download.filename, download.filename)
            }
            val description = listOf(Formatter.formatShortFileSize(context, download.size),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(download.time))).joinToString(" · ")
            result += ChannelProgram("d:${download.id}", download.filename, description, poster,
                ChannelLaunchActivity.openDownloadIntent(context, download.id).toUri(Intent.URI_INTENT_SCHEME),
                //newer downloads have bigger ids: a new one changes no other program
                weight = download.id)
        }
        return result
    }

    /** A picture of an image or a video file, where the system makes one (Android 10+) */
    private fun preview(context: Context, download: Download, kind: DownloadsNavigator.Kind): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (kind != DownloadsNavigator.Kind.IMAGE && kind != DownloadsNavigator.Kind.VIDEO) return null
        val size = Size(640, 360)
        return try {
            if (DownloadFiles.kindOf(download.filepath) == DownloadFiles.Kind.FILE) {
                val file = File(download.filepath)
                if (kind == DownloadsNavigator.Kind.IMAGE) ThumbnailUtils.createImageThumbnail(file, size, null)
                else ThumbnailUtils.createVideoThumbnail(file, size, null)
            } else {
                context.contentResolver.loadThumbnail(Uri.parse(download.filepath), size, null)
            }
        } catch (e: Exception) {
            Log.i(TAG, "No preview of ${download.filename}: $e")
            null
        }
    }

    companion object {
        private const val TAG = "DownloadsChannel"
    }
}
