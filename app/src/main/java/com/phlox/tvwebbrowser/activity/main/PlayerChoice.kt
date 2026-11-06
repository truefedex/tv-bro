package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideoMatch

/**
 * Which player shows the video a page puts in fullscreen: the built-in player (TV Bro player) or the player of the
 * page. The rules, from the top, the first that applies:
 * 1. the site leaves its videos to its own player ([builtInPlayerPreferred] false);
 * 2. the video is protected (DRM, [MediaState.drm]): the built-in player can not play it;
 * 3. no stream of it is known: the page streams it itself, or it may be one of several ([PageVideoMatch.video] null);
 * 4. it is shorter than [MIN_DURATION] seconds (ads, previews), and not a live stream;
 * 5. what the rules need came later than [WINDOW_MS] after the video went fullscreen: the page played it meanwhile,
 *    and a switch would surprise;
 * 6. else the built-in player plays it from the second the page is at ([Host.openBuiltInPlayer]).
 * The rules decide each time a video goes fullscreen, and again when the element plays another video
 * ([MediaState.source]: an ad, then the film); not while the built-in player is open ([playerOpen]), and not again on
 * a page whose video went back to the page player ([keepPagePlayer]: Up, or the built-in player could not start)
 * until another page is shown ([setPage]). [down] tells what Down does in the video mode of the page.
 * No views and no clock of its own: the time comes from [now] (elapsedRealtime, the clock of [MediaState.at]).
 */
class PlayerChoice(private val host: Host, private val now: () -> Long) {
    interface Host {
        /** The rules chose the built-in player for [video], from [position] seconds */
        fun openBuiltInPlayer(video: PageVideo, position: Double)
    }

    /** What Down does in the video mode of the page */
    enum class Down {
        /** Nothing for the video: Down gives the cursor back (no stream is known, or the video is protected) */
        CURSOR,
        /** The built-in player, from the second the page is at */
        BUILT_IN_PLAYER,
        /** The choice among the streams the video may be ([PageVideoMatch.choices]), then the built-in player */
        CHOOSE_STREAM,
    }

    /** The site of the page (or the default) leaves fullscreen videos to the built-in player; false: to its own player */
    var builtInPlayerPreferred = true
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    /** The built-in player is open: the rules wait */
    var playerOpen = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    private var fullscreen = false
    private var media: MediaState? = null
    private var match = PageVideoMatch.NONE
    private var page: String? = null
    /** The page keeps its videos in its own player */
    private var keptByPage = false
    /** A video is in fullscreen: [source] is what it plays, [since] when it went fullscreen or began to play it */
    private var watching = false
    private var source: String? = null
    private var since = 0L
    /** The rules decided for this video: the built-in player opened, or the page plays it */
    private var decided = false

    val down: Down
        get() = when {
            media?.drm == true -> Down.CURSOR
            match.video != null -> Down.BUILT_IN_PLAYER
            match.choices.isNotEmpty() -> Down.CHOOSE_STREAM
            else -> Down.CURSOR
        }

    /** The page shows a fullscreen view */
    fun setFullscreen(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        update()
    }

    /** The media of the page the keys control */
    fun setMedia(media: MediaState?) {
        this.media = media
        update()
    }

    /** The video of the page that media plays ([com.phlox.tvwebbrowser.webengine.PageVideosSupport.mediaVideo]) */
    fun setMatch(match: PageVideoMatch) {
        this.match = match
        update()
    }

    /** The page shown ([key]: the tab and its address without the fragment); another one is decided anew */
    fun setPage(key: String?) {
        if (key == page) return
        page = key
        keptByPage = false
        watching = false
        update()
    }

    /** The video went back to the page player (Up, or the built-in player could not start): the page keeps its videos */
    fun keepPagePlayer() {
        keptByPage = true
    }

    /**
     * The site chose its player anew (its site settings): the choice applies to the next video in fullscreen, also on
     * a page that kept its videos
     */
    fun siteChoiceChanged() {
        keptByPage = false
    }

    private fun update() {
        val media = media
        if (!fullscreen || media == null || !media.fullscreen) {
            watching = false
            return
        }
        if (!watching || media.source != source) {
            watching = true
            source = media.source
            since = now()
            decided = false
        }
        if (decided || playerOpen) return
        decided = decide(media)
    }

    /** @return false while the rules wait for what they need to know (the stream, the duration) */
    private fun decide(media: MediaState): Boolean {
        if (!builtInPlayerPreferred || keptByPage || media.drm) return true
        val late = now() - since > WINDOW_MS
        val video = match.video ?: return late
        if (!media.live) {
            val duration = media.duration ?: return late
            if (duration < MIN_DURATION) return true
        }
        if (late) return true
        host.openBuiltInPlayer(video, media.positionAt(now()))
        return true
    }

    companion object {
        /** Milliseconds after the video went fullscreen in which the rules may still give it to the built-in player */
        const val WINDOW_MS = 1000L
        /** Seconds: shorter videos (ads, previews) stay with the page */
        const val MIN_DURATION = 60.0
    }
}
