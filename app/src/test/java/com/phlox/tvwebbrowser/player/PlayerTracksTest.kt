package com.phlox.tvwebbrowser.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerTracksTest {
    private val texts = PlayerTracks.Texts("Auto", "Off") { "Track $it" }
    private val params = TrackSelectionParameters.Builder(RuntimeEnvironment.getApplication()).build()

    private fun video(height: Int, bitrate: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
        .setHeight(height).setWidth(height * 16 / 9).setAverageBitrate(bitrate).setPeakBitrate(bitrate).build()

    private fun audio(language: String?, channels: Int, label: String? = null) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).setChannelCount(channels).setLabel(label).build()

    private fun text(language: String) = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(language).build()

    private fun group(selected: Int?, vararg formats: Format) = Tracks.Group(TrackGroup(*formats), formats.size > 1,
        IntArray(formats.size) { C.FORMAT_HANDLED }, BooleanArray(formats.size) { it == selected })

    @Test
    fun qualitiesFromTheHighestWithAutoFirst() {
        val videos = group(1, video(720, 2_500_000), video(1080, 5_000_000), video(360, 800_000))
        val options = PlayerTracks.qualities(Tracks(listOf(videos)), params, texts)
        assertEquals(listOf("Auto (1080p)", "1080p", "720p", "360p"), options.map { it.label })
        assertEquals(listOf(true, false, false, false), options.map { it.selected })
        assertEquals(PlayerTracks.Choice.Track(videos.mediaTrackGroup, 1), options[1].choice)
    }

    @Test
    fun aChosenQualityIsSelectedAndQualitiesOfOneHeightTellTheirBitrate() {
        val videos = group(0, video(1080, 8_000_000), video(1080, 4_500_000))
        val chosen = params.buildUpon().setOverrideForType(TrackSelectionOverride(videos.mediaTrackGroup, 1)).build()
        val options = PlayerTracks.qualities(Tracks(listOf(videos)), chosen, texts)
        assertEquals(listOf("Auto", "1080p · 8.0 Mbps", "1080p · 4.5 Mbps"), options.map { it.label })
        assertEquals(listOf(false, false, true), options.map { it.selected })
    }

    @Test
    fun oneQualityIsNoChoice() {
        assertTrue(PlayerTracks.qualities(Tracks(listOf(group(0, video(1080, 0)))), params, texts).isEmpty())
    }

    @Test
    fun audioTracksByTheirLanguages() {
        val en = group(0, audio("en", 6))
        val uk = group(null, audio("uk", 2))
        val unknown = group(null, audio(null, 2))
        val options = PlayerTracks.audioTracks(Tracks(listOf(en, uk, unknown)), texts)
        assertEquals(listOf("English · 5.1", "Українська · 2.0", "Track 3 · 2.0"), options.map { it.label })
        assertEquals(listOf(true, false, false), options.map { it.selected })
        assertTrue(PlayerTracks.audioTracks(Tracks(listOf(en)), texts).isEmpty())
    }

    @Test
    fun subtitlesWithOff() {
        val en = group(null, text("en"))
        val de = group(0, text("de"))
        val options = PlayerTracks.subtitles(Tracks(listOf(en, de)), params, texts)
        assertEquals(listOf("Off", "English", "Deutsch"), options.map { it.label })
        assertEquals(listOf(false, false, true), options.map { it.selected })
        val off = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        assertEquals(listOf(true, false, false), PlayerTracks.subtitles(Tracks(listOf(en, de)), off, texts).map { it.selected })
    }

    @Test
    fun speeds() {
        assertEquals("1×", PlayerTracks.speedLabel(1f))
        assertEquals("0.75×", PlayerTracks.speedLabel(0.75f))
        assertEquals("2×", PlayerTracks.speedLabel(2f))
    }
}
