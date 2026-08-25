package com.phlox.tvwebbrowser.activity.player

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.phlox.tvwebbrowser.R

/**
 * TV Bro's built-in video player (ExoPlayer/media3).
 *
 * It covers cases the external-player intent cannot handle:
 *  - Referer/Cookie/User-Agent are attached to EVERY request (manifest, sub
 *    playlists and segments), so referer-protected streams work. External
 *    players only apply such headers to the first URL, which makes those
 *    streams fail with 403/404 on the segment requests.
 *  - HLS manifests served under a disguised name are decoded correctly by
 *    forcing the mime type instead of trusting the extension.
 *  - Remote control keys are handled in-app by PlayerView.
 */
@UnstableApi
class ExoPlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_UA = "ua"
        const val EXTRA_COOKIE = "cookie"
    }

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_UA)
        val cookie = intent.getStringExtra(EXTRA_COOKIE)

        val playerView = PlayerView(this)
        playerView.keepScreenOn = true
        setContentView(playerView)

        val headers = HashMap<String, String>()
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        playerView.player = exo
        player = exo

        //Force the mime type instead of trusting the extension or content type:
        //assume HLS for everything except clearly progressive files, because
        //disguised manifests are served as text/html.
        val path = url.substringBefore('?').lowercase()
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        when {
            path.endsWith(".mpd") ->
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
            path.endsWith(".mkv") || path.endsWith(".mov") -> {
                //progressive: let ExoPlayer detect the container itself
            }
            else ->
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@ExoPlayerActivity,
                    getString(R.string.player_error, error.errorCodeName), Toast.LENGTH_LONG).show()
            }
        })
        exo.setMediaItem(mediaItemBuilder.build())
        exo.playWhenReady = true
        exo.prepare()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
