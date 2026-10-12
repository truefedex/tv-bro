package com.phlox.tvwebbrowser.activity.main.dialogs.media

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewMediaCandidateItemBinding
import com.phlox.tvwebbrowser.player.MediaMetadata
import com.phlox.tvwebbrowser.player.MediaMetadataCache
import com.phlox.tvwebbrowser.webengine.MediaCandidate
import java.util.Locale

class MediaListAdapter(context: Context, private val items: List<MediaCandidate>) :
    BaseAdapter(), MediaMetadataCache.Listener {

    private val inflater = LayoutInflater.from(context)

    init {
        MediaMetadataCache.addListener(this)
    }

    /** Stop listening once the dialog is dismissed. */
    fun release() {
        MediaMetadataCache.removeListener(this)
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): MediaCandidate = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onMetadataUpdated(url: String) {
        if (items.any { it.url == url }) notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.view_media_candidate_item, parent, false)
        val vb = ViewMediaCandidateItemBinding.bind(view)
        val candidate = items[position]
        val uri = Uri.parse(candidate.url)
        vb.tvTitle.text = title(uri, candidate)
        vb.tvSubtitle.text = subtitle(uri, candidate)
        vb.tvMeta.text = metaText(MediaMetadataCache.get(candidate.url))
        return view
    }

    private fun title(uri: Uri, candidate: MediaCandidate): String {
        val last = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
        return if (last.isNotBlank()) Uri.decode(last) else (uri.host ?: candidate.url)
    }

    private fun subtitle(uri: Uri, candidate: MediaCandidate): String {
        val host = uri.host
        return if (host.isNullOrBlank()) candidate.kind.name else "${candidate.kind.name} · $host"
    }

    private fun metaText(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        val lines = mutableListOf<String>()
        metadata.resolution?.let(lines::add)
        val details = mutableListOf<String>()
        if (metadata.languages.isNotEmpty()) details.add(metadata.languages.joinToString("/"))
        metadata.durationMs?.let { details.add(formatDuration(it)) }
        if (details.isNotEmpty()) lines.add(details.joinToString(" · "))
        return lines.joinToString("\n").ifBlank { null }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
