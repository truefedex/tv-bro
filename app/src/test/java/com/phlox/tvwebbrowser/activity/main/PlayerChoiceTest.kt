package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideoMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerChoiceTest {
    private var time = 100_000L
    private val opened = ArrayList<Pair<String, Double>>()
    private val choice = PlayerChoice(object : PlayerChoice.Host {
        override fun openBuiltInPlayer(video: PageVideo, position: Double) {
            opened += video.url to position
        }
    }) { time }

    private val stream = PageVideo("https://cdn.example/film/master.m3u8", PageVideo.Kind.HLS)
    private val other = PageVideo("https://cdn.example/promo/index.m3u8", PageVideo.Kind.HLS)

    private fun media(position: Double = 725.0, duration: Double? = 1352.0, fullscreen: Boolean = true,
                      live: Boolean = false, drm: Boolean = false, source: String? = "blob:https://kino.example/film",
                      at: Long = time) =
        MediaState(playing = true, position = position, duration = duration, fullscreen = fullscreen, at = at,
            live = live, drm = drm, source = source)

    /** The page plays [media] of [match] and puts it in fullscreen */
    private fun goFullscreen(media: MediaState = media(), match: PageVideoMatch = PageVideoMatch(stream)) {
        choice.setPage("1|https://kino.example/film")
        choice.setMatch(match)
        choice.setMedia(media)
        choice.setFullscreen(true)
    }

    @Test
    fun aLongVideoWithAKnownStreamGoesToTheBuiltInPlayerFromTheSameSecond() {
        //the page reported its position half a second ago and played on
        goFullscreen(media(position = 725.0, at = time - 500))
        assertEquals(listOf(stream.url to 725.5), opened)
        assertEquals(PlayerChoice.Down.BUILT_IN_PLAYER, choice.down)
    }

    @Test
    fun aSiteSetToItsOwnPlayerKeepsTheVideoAndDownStillOpensTheBuiltInPlayer() {
        choice.builtInPlayerPreferred = false
        goFullscreen()
        assertEquals(emptyList<Pair<String, Double>>(), opened)
        assertEquals(PlayerChoice.Down.BUILT_IN_PLAYER, choice.down)
    }

    @Test
    fun aProtectedVideoStaysWithThePageAndDownGivesTheCursor() {
        goFullscreen(media(drm = true))
        assertEquals(emptyList<Pair<String, Double>>(), opened)
        assertEquals(PlayerChoice.Down.CURSOR, choice.down)
    }

    @Test
    fun aVideoThePageStreamsItselfStaysWithIt() {
        goFullscreen(match = PageVideoMatch.NONE)
        assertEquals(PlayerChoice.Down.CURSOR, choice.down)
        time += 5000
        choice.setMedia(media())
        assertEquals(emptyList<Pair<String, Double>>(), opened)
    }

    @Test
    fun aStreamFoundWithinASecondStillGoesToTheBuiltInPlayer() {
        goFullscreen(match = PageVideoMatch.NONE)
        time += 800
        choice.setMatch(PageVideoMatch(stream))
        assertEquals(listOf(stream.url to 725.8), opened)
    }

    @Test
    fun aStreamFoundLaterStaysWithThePageButDownOpensIt() {
        goFullscreen(match = PageVideoMatch.NONE)
        time += 1500
        choice.setMatch(PageVideoMatch(stream))
        assertEquals(emptyList<Pair<String, Double>>(), opened)
        assertEquals(PlayerChoice.Down.BUILT_IN_PLAYER, choice.down)
    }

    @Test
    fun severalStreamsLeaveTheChoiceToTheUser() {
        goFullscreen(match = PageVideoMatch(null, listOf(stream, other)))
        assertEquals(emptyList<Pair<String, Double>>(), opened)
        assertEquals(PlayerChoice.Down.CHOOSE_STREAM, choice.down)
    }

    @Test
    fun aShortVideoStaysWithThePageUntilTheElementPlaysAnother() {
        //an ad first
        goFullscreen(media(position = 3.0, duration = 30.0, source = "blob:https://kino.example/ad"))
        assertEquals(emptyList<Pair<String, Double>>(), opened)
        time += 30_000
        //then the film: decided again, its second counts from now
        choice.setMedia(media(position = 0.0, source = "blob:https://kino.example/film"))
        assertEquals(listOf(stream.url to 0.0), opened)
    }

    @Test
    fun aLiveStreamGoesToTheBuiltInPlayer() {
        goFullscreen(media(duration = null, live = true))
        assertEquals(1, opened.size)
    }

    @Test
    fun aLengthKnownWithinASecondCounts() {
        goFullscreen(media(duration = null))
        assertEquals(emptyList<Pair<String, Double>>(), opened)
        time += 500
        choice.setMedia(media())
        assertEquals(1, opened.size)
    }

    @Test
    fun aLengthStillUnknownAfterASecondStaysWithThePage() {
        goFullscreen(media(duration = null))
        time += 1200
        choice.setMedia(media())
        assertEquals(emptyList<Pair<String, Double>>(), opened)
    }

    @Test
    fun aStreamFoundLateForTheNextVideoOfTheElementCountsFromThatVideo() {
        goFullscreen(media(duration = 20.0, source = "https://ads.example/ad.mp4"), PageVideoMatch.NONE)
        time += 20_000
        choice.setMedia(media(source = "blob:https://kino.example/film"))
        time += 700
        choice.setMatch(PageVideoMatch(stream))
        assertEquals(1, opened.size)
    }

    @Test
    fun afterUpThePageKeepsItsVideosUntilAnotherPage() {
        goFullscreen()
        assertEquals(1, opened.size)
        choice.playerOpen = true
        //Up: the page plays it on
        choice.keepPagePlayer()
        choice.playerOpen = false
        choice.setMedia(media(source = "blob:https://kino.example/next"))
        choice.setFullscreen(false)
        choice.setFullscreen(true)
        assertEquals(1, opened.size)
        assertEquals(PlayerChoice.Down.BUILT_IN_PLAYER, choice.down)
        //another page decides again
        choice.setPage("1|https://kino.example/other-film")
        assertEquals(2, opened.size)
    }

    @Test
    fun aPlayerChosenInTheSiteSettingsAppliesOnThePageThatKeptItsVideos() {
        //the site leaves its videos to its own player, and the page kept its video (Up from the built-in player)
        choice.builtInPlayerPreferred = false
        goFullscreen()
        choice.keepPagePlayer()
        choice.setFullscreen(false)
        //the setting alone leaves the page its videos
        choice.builtInPlayerPreferred = true
        choice.setFullscreen(true)
        assertEquals(emptyList<Pair<String, Double>>(), opened)
        choice.setFullscreen(false)
        //chosen in the site settings: the next fullscreen of the same page goes to the built-in player
        choice.siteChoiceChanged()
        choice.setFullscreen(true)
        assertEquals(listOf(stream.url to 725.0), opened)
    }

    @Test
    fun nothingOpensWhileTheBuiltInPlayerIsOpen() {
        goFullscreen()
        choice.playerOpen = true
        choice.setMedia(media(source = "blob:https://kino.example/next"))
        choice.setMatch(PageVideoMatch(other))
        assertEquals(1, opened.size)
    }

    @Test
    fun eachTimeAVideoGoesFullscreenItIsDecidedAgain() {
        goFullscreen()
        choice.playerOpen = true
        //Back: the page leaves fullscreen
        choice.playerOpen = false
        choice.setFullscreen(false)
        time += 10_000
        choice.setFullscreen(true)
        assertEquals(2, opened.size)
    }

    @Test
    fun aVideoOutsideTheFullscreenViewIsNotTaken() {
        goFullscreen(media(fullscreen = false))
        assertEquals(emptyList<Pair<String, Double>>(), opened)
    }

    @Test
    fun leavingFullscreenBeforeTheStreamIsKnownEndsTheWait() {
        goFullscreen(match = PageVideoMatch.NONE)
        choice.setFullscreen(false)
        choice.setMatch(PageVideoMatch(stream))
        assertEquals(emptyList<Pair<String, Double>>(), opened)
    }
}
