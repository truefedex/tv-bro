package com.phlox.tvwebbrowser.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import java.util.Locale

/**
 * The choices of the menu of the built-in player, from the tracks of the video (Media3 [Tracks]) and the
 * selection the user made ([TrackSelectionParameters]): the qualities (Auto, the adaptive choice of the player,
 * then each quality from the highest), the audio tracks and the subtitles (Off, then each language).
 */
object PlayerTracks {
    /** The texts the options need (localized by the caller) */
    class Texts(val auto: String, val off: String, val track: (Int) -> String)

    data class Option(val label: String, val selected: Boolean, val choice: Choice)

    sealed interface Choice {
        /** The quality the player chooses by itself */
        data object Auto : Choice
        /** No subtitles */
        data object Off : Choice
        /** One quality */
        data class Track(val group: TrackGroup, val index: Int) : Choice
        /** An audio track or a language of the subtitles (all its tracks, the player chooses among them) */
        data class Group(val group: TrackGroup) : Choice
    }

    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Auto and the qualities the device can play; empty when there is no choice (one quality) */
    fun qualities(tracks: Tracks, parameters: TrackSelectionParameters, texts: Texts): List<Option> {
        val qualities = groups(tracks, C.TRACK_TYPE_VIDEO).flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }.sortedWith(compareByDescending<Pair<Tracks.Group, Int>> { it.first.getTrackFormat(it.second).height }
            .thenByDescending { it.first.getTrackFormat(it.second).bitrate })
        if (qualities.size < 2) return emptyList()
        val chosen = parameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        val heights = qualities.map { it.first.getTrackFormat(it.second).height }
        val options = qualities.map { (group, index) ->
            val format = group.getTrackFormat(index)
            //two qualities of one height are told apart by their bitrate
            val label = qualityLabel(format, heights.count { it == format.height } > 1)
            val selected = chosen != null && chosen.mediaTrackGroup == group.mediaTrackGroup && index in chosen.trackIndices
            Option(label, selected, Choice.Track(group.mediaTrackGroup, index))
        }
        val playing = qualities.firstOrNull { (group, index) -> group.isTrackSelected(index) }
        val auto = if (playing != null && chosen == null) {
            "${texts.auto} (${qualityLabel(playing.first.getTrackFormat(playing.second), false)})"
        } else texts.auto
        return listOf(Option(auto, chosen == null, Choice.Auto)) + options
    }

    /** The audio tracks; empty when there is only one */
    fun audioTracks(tracks: Tracks, texts: Texts): List<Option> {
        val groups = groups(tracks, C.TRACK_TYPE_AUDIO)
        if (groups.size < 2) return emptyList()
        return groups.mapIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val label = listOfNotNull(trackName(format) ?: texts.track(i + 1), channels(format.channelCount))
                .joinToString(" · ")
            Option(label, group.isSelected, Choice.Group(group.mediaTrackGroup))
        }
    }

    /** Off and the subtitles; empty when there are none */
    fun subtitles(tracks: Tracks, parameters: TrackSelectionParameters, texts: Texts): List<Option> {
        val groups = groups(tracks, C.TRACK_TYPE_TEXT)
        if (groups.isEmpty()) return emptyList()
        val disabled = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes
        val options = groups.mapIndexed { i, group ->
            Option(trackName(group.getTrackFormat(0)) ?: texts.track(i + 1), !disabled && group.isSelected,
                Choice.Group(group.mediaTrackGroup))
        }
        return listOf(Option(texts.off, options.none { it.selected }, Choice.Off)) + options
    }

    /** "1080p", with the bitrate when [withBitrate] ("1080p · 4.5 Mbps") */
    fun qualityLabel(format: Format, withBitrate: Boolean): String {
        val name = if (format.height > 0) "${format.height}p" else if (format.bitrate > 0) mbps(format.bitrate) else "?"
        return if (withBitrate && format.height > 0 && format.bitrate > 0) "$name · ${mbps(format.bitrate)}" else name
    }

    /** "2×", "0.75×" */
    fun speedLabel(speed: Float): String = (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"

    private fun mbps(bitrate: Int) = String.format(Locale.ROOT, "%.1f Mbps", bitrate / 1_000_000.0)

    /** The label of the track, else its language in the language of the user */
    private fun trackName(format: Format): String? {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED } ?: return null
        val locale = Locale.forLanguageTag(language)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { language }
    }

    private fun channels(count: Int): String? = when (count) {
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    private fun groups(tracks: Tracks, type: Int): List<Tracks.Group> =
        tracks.groups.filter { it.type == type && (0 until it.length).any { i -> it.isTrackSupported(i) } }
}
