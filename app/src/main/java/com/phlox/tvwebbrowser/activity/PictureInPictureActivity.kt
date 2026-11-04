package com.phlox.tvwebbrowser.activity

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.databinding.ActivityPictureInPictureBinding
import com.phlox.tvwebbrowser.player.NativePlayer
import com.phlox.tvwebbrowser.player.PictureInPicturePlayback
import com.phlox.tvwebbrowser.player.PictureInPictureSupport
import com.phlox.tvwebbrowser.player.PlayerMediaSession
import com.phlox.tvwebbrowser.utils.ExternalVideoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The built-in player in a picture-in-picture window over the browser and other apps: it shows the player the screen
 * of the player handed to [PictureInPicturePlayback] and goes into the window as soon as it is shown. It has a task of
 * its own, so the browser stays usable behind the window and closing the window leaves the browser alone. Expanded,
 * it gives the player back to the browser activity of its process ([browserActivity]), which shows it from where it
 * is; closed, the player is released. The window has one control: play or pause. A media session tells the system
 * the title and takes the media keys of the remote while the window floats over another app ([PlayerMediaSession]).
 */
open class PictureInPictureActivity : AppCompatActivity() {
    companion object {
        private val TAG = PictureInPictureActivity::class.java.simpleName
        private const val ACTION_TOGGLE = "com.phlox.tvwebbrowser.action.PIP_TOGGLE_PLAYBACK"
        private const val ACTION_ICON_SIZE_DP = 36

        /** Starts the window of the process of [browser] (normal or incognito) */
        fun start(browser: Activity) {
            val window = if (browser is IncognitoModeMainActivity) IncognitoPictureInPictureActivity::class.java
            else PictureInPictureActivity::class.java
            browser.startActivity(Intent(browser, window), noAnimation(browser))
        }

        private fun noAnimation(context: Context) = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
    }

    /** The browser activity of this process */
    protected open val browserActivity: Class<out MainActivity> get() = MainActivity::class.java

    private lateinit var vb: ActivityPictureInPictureBinding
    /** The player this window shows; null when there was none to show */
    private var player: NativePlayer? = null
    private var receiverRegistered = false
    private var entered = false
    /** The window was expanded: the player goes back to the browser when the activity resumes */
    private var leftWindow = false
    /** The player went back to the browser, so it is not released here */
    private var handedBack = false
    private var shownParams: Pair<Boolean, Rational>? = null
    private var stateJob: Job? = null
    /** While this window has the player */
    private var mediaSession: PlayerMediaSession? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TOGGLE) player?.togglePlayback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = PictureInPicturePlayback.player
        if (player == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            finish()
            return
        }
        vb = ActivityPictureInPictureBinding.inflate(layoutInflater)
        setContentView(vb.root)
        show(player)
        ContextCompat.registerReceiver(this, controlReceiver, IntentFilter(ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        //the expanded window has no controls: Back gives the video to the browser too
        onBackPressedDispatcher.addCallback(this) { handBack() }
    }

    /** A new video while this window is still there (a single task): it shows that one */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val player = PictureInPicturePlayback.player ?: return
        if (player === this.player || this.player == null) return
        handedBack = false
        leftWindow = false
        if (!isInPictureInPictureMode) entered = false
        show(player)
    }

    private fun show(player: NativePlayer) {
        this.player = player
        PictureInPicturePlayback.attach(this)
        vb.vPlayer.player = player.exoPlayer
        if (mediaSession == null) mediaSession = PlayerMediaSession(this)
        mediaSession?.show(player)
        stateJob?.cancel()
        stateJob = lifecycleScope.launch {
            player.state.collect { state ->
                if (state == null) {
                    finish()
                    return@collect
                }
                mediaSession?.update(state)
                if (entered) updateParams(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (player == null || handedBack) return
        if (!entered) {
            entered = true
            //after the first layout, for the bounds of the video
            window.decorView.post { enter() }
        } else if (leftWindow && !isInPictureInPictureMode) {
            handBack()
        }
    }

    private fun enter() {
        if (handedBack || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val inWindow = try {
            enterPictureInPictureMode(params(player?.state?.value))
        } catch (e: Exception) {
            Log.w(TAG, "No picture-in-picture window", e)
            false
        }
        if (!inWindow) handBack()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            leftWindow = false
            return
        }
        //expanded: the activity resumes next; closed: it stops (that may have come first)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            leftWindow = true
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) handBack()
        } else {
            close()
        }
    }

    override fun onStop() {
        super.onStop()
        //the window was closed (or the screen went off)
        if (player != null && !handedBack && !isChangingConfigurations) close()
    }

    override fun onDestroy() {
        releaseMediaSession()
        if (receiverRegistered) unregisterReceiver(controlReceiver)
        if (!handedBack) player?.let { PictureInPicturePlayback.end(it) }
        //the view lets go of the player, which may play on in the browser
        if (player != null) vb.vPlayer.player = null
        super.onDestroy()
    }

    /** The video goes on in the built-in player of the browser, from where it is */
    private fun handBack() {
        if (handedBack) return
        handedBack = true
        //the media keys go back to the browser, which has the focus
        releaseMediaSession()
        if (PictureInPicturePlayback.player === player) {
            startActivity(Intent(this, browserActivity).putExtra(MainActivity.KEY_SHOW_PIP_PLAYER, true),
                noAnimation(this))
        }
        finish()
    }

    private fun close() {
        releaseMediaSession()
        player?.let { PictureInPicturePlayback.end(it) }
        if (!isFinishing) finish()
    }

    private fun releaseMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    /** The shape of the window and the control follow the video; set again only when one of them changes */
    private fun updateParams(state: NativePlayer.State) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || handedBack || isFinishing) return
        val shown = Pair(state.media.playing, PictureInPictureSupport.aspectRatio(state.aspectRatio))
        if (shown == shownParams) return
        try {
            setPictureInPictureParams(params(state))
        } catch (e: Exception) {
            Log.w(TAG, "Picture-in-picture parameters", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun params(state: NativePlayer.State?): PictureInPictureParams {
        val playing = state?.media?.playing == true
        val ratio = PictureInPictureSupport.aspectRatio(state?.aspectRatio)
        shownParams = Pair(playing, ratio)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(toggleAction(playing)))
        val bounds = Rect()
        if (vb.vPlayer.videoSurfaceView?.getGlobalVisibleRect(bounds) == true && !bounds.isEmpty) {
            builder.setSourceRectHint(bounds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state != null) {
            builder.setTitle(ExternalVideoPlayer.title(state.video))
            Uri.parse(state.video.url).host?.let { builder.setSubtitle(it) }
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun toggleAction(playing: Boolean): RemoteAction {
        val label = getString(if (playing) R.string.pause else R.string.play)
        val intent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(actionIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play), label, label, intent)
    }

    /**
     * A white bitmap of the glyph: the system draws the controls, and the tint of the app's icons is a theme
     * attribute it can not resolve
     */
    private fun actionIcon(@DrawableRes glyph: Int): Icon {
        val size = (ACTION_ICON_SIZE_DP * resources.displayMetrics.density).toInt()
        val drawable = AppCompatResources.getDrawable(this, glyph)!!.mutate()
        drawable.setTint(Color.WHITE)
        return Icon.createWithBitmap(drawable.toBitmap(size, size))
    }
}
