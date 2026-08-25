package com.phlox.tvwebbrowser.activity.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
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

        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var resizeModeIndex = 0

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
        this.playerView = playerView

        val headers = HashMap<String, String>()
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        //Cheap TV boxes often ship a broken hardware decoder. Allow ExoPlayer to
        //fall back to another (usually software) decoder when the first one fails
        //to initialise or misbehaves, instead of giving up on the stream.
        //EXTENSION_RENDERER_MODE_ON keeps the platform decoders first and brings in
        //the bundled FFmpeg audio decoders only for formats the device cannot
        //handle itself (AC3, E-AC3, DTS...), which otherwise play without sound.
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exo = ExoPlayer.Builder(this, renderersFactory)
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
                //Typically ERROR_CODE_IO_BAD_HTTP_STATUS: the auto-picked stream is
                //protected or wrong. Report it and close, so the browser can offer
                //the other candidates instead of leaving a black screen behind.
                Toast.makeText(this@ExoPlayerActivity,
                    getString(R.string.player_error, error.errorCodeName), Toast.LENGTH_LONG).show()
                PlaybackFailure.last = error.errorCodeName
                finish()
            }
        })
        exo.setMediaItem(mediaItemBuilder.build())
        exo.playWhenReady = true
        exo.prepare()
    }

    /**
     * Subtitles, audio tracks and video scaling, reachable with the menu key.
     * ExoPlayer renders subtitles by itself once a text track is selected, but it
     * does not pick one on its own, so this is how the user turns them on.
     */
    private fun showSettings() {
        if (player == null) return
        val labels = arrayOf(
            getString(R.string.player_subtitles),
            getString(R.string.player_audio_track),
            getString(R.string.player_video_scale)
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showTrackChooser(C.TRACK_TYPE_TEXT)
                    1 -> showTrackChooser(C.TRACK_TYPE_AUDIO)
                    2 -> cycleResizeMode()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTrackChooser(trackType: Int) {
        val exo = player ?: return
        //flatten the selectable tracks of this type into a single list
        val entries = ArrayList<Pair<Tracks.Group, Int>>()
        for (group in exo.currentTracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) entries.add(group to i)
            }
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.player_no_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        val offLabel = getString(R.string.player_track_off)
        val labels = ArrayList<String>()
        if (trackType == C.TRACK_TYPE_TEXT) labels.add(offLabel)
        for ((group, i) in entries) {
            val format = group.getTrackFormat(i)
            val name = format.label ?: format.language ?: format.sampleMimeType ?: "?"
            labels.add(name)
        }
        val title = if (trackType == C.TRACK_TYPE_TEXT) R.string.player_subtitles
            else R.string.player_audio_track
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                val offset = if (trackType == C.TRACK_TYPE_TEXT) 1 else 0
                if (trackType == C.TRACK_TYPE_TEXT && which == 0) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, true)
                        .build()
                    return@setItems
                }
                val (group, index) = entries[which - offset]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % RESIZE_MODES.size
        playerView?.resizeMode = RESIZE_MODES[resizeModeIndex]
        val name = when (RESIZE_MODES[resizeModeIndex]) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.player_scale_zoom
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.player_scale_fill
            else -> R.string.player_scale_fit
        }
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
    }

    /**
     * PlayerView swallows key events for its own control bar, so onKeyDown never
     * sees them. dispatchKeyEvent runs before the view hierarchy, which is the
     * only reliable place to catch the settings key.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isSettingsKey = event.keyCode in PlayerKeys.SETTINGS
        if (isSettingsKey) {
            if (event.action == KeyEvent.ACTION_DOWN) showSettings()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        playerView = null
    }
}
