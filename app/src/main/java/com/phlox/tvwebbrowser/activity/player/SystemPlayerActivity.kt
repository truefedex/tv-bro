package com.phlox.tvwebbrowser.activity.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.phlox.tvwebbrowser.R

/**
 * Fallback player built on the platform MediaPlayer.
 *
 * It costs nothing in app size and is a completely different implementation from
 * ExoPlayer, so a stream that trips one of them may still play in the other. It
 * is deliberately minimal: the built-in player remains the primary one.
 *
 * Referer, Cookie and User-Agent are passed as request headers, which the
 * platform applies to the stream including its segments.
 */
class SystemPlayerActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_UA = "ua"
        const val EXTRA_COOKIE = "cookie"

        private const val SEEK_STEP = 10000
        private const val CONTROLS_TIMEOUT = 3500L
        private const val PROGRESS_INTERVAL = 500L
        private const val ACCENT = 0xFF4DB6FF.toInt()
    }

    private var player: MediaPlayer? = null
    private var status: TextView? = null
    private var surfaceView: SurfaceView? = null
    private var subtitleView: TextView? = null
    private var scaleMode = 0
    private var selectedSubtitle = -1
    private var controls: View? = null
    private var progress: ProgressBar? = null
    private var timeLabel: TextView? = null
    private var buttonRow: LinearLayout? = null
    private var playPauseButton: TextView? = null
    private var prepared = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { controls?.visibility = View.GONE }
    private val updateProgress = object : Runnable {
        override fun run() {
            refreshProgress()
            uiHandler.postDelayed(this, PROGRESS_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (intent.getStringExtra(EXTRA_URL).isNullOrEmpty()) {
            finish()
            return
        }

        val root = FrameLayout(this)
        val surface = SurfaceView(this)
        surfaceView = surface
        root.addView(surface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER))

        val subs = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(subs, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).also { it.bottomMargin = 120 })
        subtitleView = subs

        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setText(R.string.loading)
        }
        root.addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        status = text

        root.addView(buildControls(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        setContentView(root)
        surface.holder.addCallback(this)
    }

    /**
     * Layout follows what people expect from a TV player: transport controls in
     * the middle, options and the clock on the right, all above a thin progress
     * line.
     */
    private fun buildControls(): View {
        val scrim = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0xE6000000.toInt())
        )
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = scrim
            setPadding(dp(40), dp(44), dp(40), dp(24))
            visibility = View.GONE
        }

        val p = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = progressDrawable()
        }
        bar.addView(p, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(5)))

        //A FrameLayout keeps the transport controls on the left and the options
        //on the right without nested weights, which stretched the bar before.
        val row = FrameLayout(this)

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(makeButton("\u25C0\u25C0") { seekBy(-SEEK_STEP) })
        playPauseButton = makeButton("\u275A\u275A") { togglePlayPause() }
        left.addView(playPauseButton)
        left.addView(makeButton("\u25B6\u25B6") { seekBy(SEEK_STEP) })

        val time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        left.addView(time, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(8) })
        row.addView(left, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        right.addView(makeButton("CC") {
            showTrackChooser(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
        })
        right.addView(makeButton("\u266A") {
            showTrackChooser(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)
        })
        right.addView(makeButton("\u26F6") { cycleScale() })
        row.addView(right, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL))

        bar.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) })

        controls = bar
        progress = p
        timeLabel = time
        buttonRow = left
        return bar
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Thin rounded track, filled with the accent colour. */
    private fun progressDrawable(): LayerDrawable {
        val track = GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor(0x40FFFFFF)
        }
        val fill = GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor(ACCENT)
        }
        val layers = LayerDrawable(arrayOf(track,
            ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        return layers
    }

    /** Plain focusable chips instead of the dated default button chrome. */
    private fun makeButton(label: String, onClick: () -> Unit): TextView {
        val normal = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x33FFFFFF)
        }
        val focused = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(ACCENT)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = states
            minWidth = dp(58)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.rightMargin = dp(8) }
            setOnClickListener {
                onClick()
                scheduleHide()
            }
        }
    }

    private fun togglePlayPause() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            refreshProgress()
            showControls(focusButtons = true)
        } else {
            mp.start()
            refreshProgress()
            hideControlsNow()
        }
    }

    private fun seekBy(deltaMs: Int) {
        val mp = player ?: return
        val target = (mp.currentPosition + deltaMs).coerceIn(0, maxOf(mp.duration, 0))
        mp.seekTo(target)
        refreshProgress()
    }

    private fun showControls(focusButtons: Boolean = false) {
        controls?.visibility = View.VISIBLE
        refreshProgress()
        if (focusButtons) playPauseButton?.requestFocus()
        scheduleHide()
    }

    /**
     * Hide the bar again only while something is playing. When playback is
     * paused the user is picking an option, so the bar has to stay put.
     */
    private fun scheduleHide() {
        uiHandler.removeCallbacks(hideControls)
        if (player?.isPlaying == true) {
            uiHandler.postDelayed(hideControls, CONTROLS_TIMEOUT)
        }
    }

    private fun hideControlsNow() {
        uiHandler.removeCallbacks(hideControls)
        controls?.visibility = View.GONE
    }

    private fun refreshProgress() {
        val mp = player ?: return
        if (!prepared) return
        val duration = mp.duration
        val position = mp.currentPosition
        if (duration > 0) {
            progress?.progress = (position.toLong() * 1000 / duration).toInt()
        }
        timeLabel?.text = "${formatTime(position)} / ${
            if (duration > 0) formatTime(duration) else "--:--"}"
        playPauseButton?.text = if (mp.isPlaying) "\u275A\u275A" else "\u25B6"
    }

    private fun formatTime(ms: Int): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (player != null) return
        val url = intent.getStringExtra(EXTRA_URL) ?: return

        val headers = HashMap<String, String>()
        intent.getStringExtra(EXTRA_REFERER)?.let { headers["Referer"] = it }
        intent.getStringExtra(EXTRA_COOKIE)?.let { headers["Cookie"] = it }
        intent.getStringExtra(EXTRA_UA)?.let { headers["User-Agent"] = it }

        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDisplay(holder)
            mp.setDataSource(this, Uri.parse(url), headers)
            mp.setOnPreparedListener {
                prepared = true
                status?.text = ""
                it.start()
                applyScale()
                showControls()
                uiHandler.post(updateProgress)
            }
            mp.setOnVideoSizeChangedListener { _, _, _ -> applyScale() }
            mp.setOnTimedTextListener { _, text ->
                uiHandler.post { subtitleView?.text = text?.text ?: "" }
            }
            mp.setOnErrorListener { _, what, extra ->
                fail(describeError(what, extra))
                true
            }
            mp.setOnCompletionListener { finish() }
            mp.prepareAsync()
        } catch (e: Exception) {
            fail(e.javaClass.simpleName)
        }
    }

    /** MediaPlayer only reports numeric codes, so name the common ones. */
    private fun describeError(what: Int, extra: Int): String = when {
        what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED ->
            "unsupported"
        what == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_IO -> "network"
        what == MediaPlayer.MEDIA_ERROR_MALFORMED || extra == MediaPlayer.MEDIA_ERROR_MALFORMED ->
            "malformed"
        what == MediaPlayer.MEDIA_ERROR_TIMED_OUT || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT ->
            "timeout"
        else -> "$what/$extra"
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    /** Report the failure so the browser can move on to the next candidate. */
    private fun fail(reason: String) {
        if (isFinishing) return
        Toast.makeText(this, getString(R.string.player_error, reason), Toast.LENGTH_LONG).show()
        PlaybackFailure.last = reason
        finish()
    }

    /**
     * Audio tracks, subtitles and scaling. The platform MediaPlayer exposes far
     * less than ExoPlayer here: subtitles arrive as plain timed text that we draw
     * ourselves, and some streams expose no selectable tracks at all.
     */
    private fun showSettings() {
        if (player == null || !prepared) return
        val labels = arrayOf(
            getString(R.string.player_subtitles),
            getString(R.string.player_audio_track),
            getString(R.string.player_video_scale)
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showTrackChooser(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
                    1 -> showTrackChooser(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)
                    2 -> cycleScale()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTrackChooser(trackType: Int) {
        val mp = player ?: return
        val tracks = try { mp.trackInfo } catch (e: Exception) { null }
        val indices = ArrayList<Int>()
        val labels = ArrayList<String>()
        val subtitles = trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
        if (subtitles) labels.add(getString(R.string.player_track_off))
        tracks?.forEachIndexed { i, info ->
            val matches = info.trackType == trackType ||
                    (subtitles && info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE)
            if (matches) {
                indices.add(i)
                labels.add(info.language ?: "#${'$'}{i + 1}")
            }
        }
        if (indices.isEmpty()) {
            Toast.makeText(this, R.string.player_no_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        val title = if (subtitles) R.string.player_subtitles else R.string.player_audio_track
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (subtitles && which == 0) {
                    subtitleView?.text = ""
                    try { mp.deselectTrack(selectedSubtitle) } catch (e: Exception) {}
                    selectedSubtitle = -1
                    return@setItems
                }
                val index = indices[which - if (subtitles) 1 else 0]
                try {
                    mp.selectTrack(index)
                    if (subtitles) selectedSubtitle = index
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.player_no_tracks, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cycleScale() {
        scaleMode = (scaleMode + 1) % 3
        applyScale()
        val name = when (scaleMode) {
            1 -> R.string.player_scale_zoom
            2 -> R.string.player_scale_fill
            else -> R.string.player_scale_fit
        }
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
    }

    /** SurfaceView has no scaling of its own, so resize it to match the mode. */
    private fun applyScale() {
        val mp = player ?: return
        val surface = surfaceView ?: return
        val parent = surface.parent as? View ?: return
        val vw = mp.videoWidth
        val vh = mp.videoHeight
        val cw = parent.width
        val ch = parent.height
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return
        val videoRatio = vw.toFloat() / vh
        val screenRatio = cw.toFloat() / ch
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        when (scaleMode) {
            0 -> if (videoRatio > screenRatio) {
                lp.width = cw; lp.height = (cw / videoRatio).toInt()
            } else {
                lp.height = ch; lp.width = (ch * videoRatio).toInt()
            }
            1 -> if (videoRatio > screenRatio) {
                lp.height = ch; lp.width = (ch * videoRatio).toInt()
            } else {
                lp.width = cw; lp.height = (cw / videoRatio).toInt()
            }
            else -> { lp.width = cw; lp.height = ch }
        }
        lp.gravity = Gravity.CENTER
        surface.layoutParams = lp
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in PlayerKeys.SETTINGS) {
            if (event.action == KeyEvent.ACTION_DOWN) showSettings()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val mp = player
        if (mp == null || !prepared) return super.dispatchKeyEvent(event)

        //The dedicated play/pause key works no matter where the focus is
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            togglePlayPause()
            return true
        }

        //While a button is focused the arrows belong to it, not to seeking
        val onButtons = controls?.visibility == View.VISIBLE && controls?.findFocus() != null
        if (onButtons) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideControlsNow()
                return true
            }
            scheduleHide()
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-SEEK_STEP)
                showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(SEEK_STEP)
                showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showControls(focusButtons = true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showControls()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (prepared) player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(updateProgress)
        uiHandler.removeCallbacks(hideControls)
        player?.release()
        player = null
        status = null
        controls = null
    }
}
