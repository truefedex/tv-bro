package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewTvPairedPhoneRowBinding
import com.phlox.tvwebbrowser.databinding.ViewTvSyncScreenBinding
import com.phlox.tvwebbrowser.model.PairedDevice
import com.phlox.tvwebbrowser.sync.QrBitmap
import com.phlox.tvwebbrowser.sync.SyncLink
import com.phlox.tvwebbrowser.utils.themeColor
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The screen of the TV that pairs phones, over the whole window: the QR code of the pairing (a phone that
 * opens its link pairs while the screen is open), the paired phones (OK on one asks whether to forget it)
 * and Undo of the last change of bookmarks made by a phone. Back closes it; [Callback.onClosed] gives the
 * focus back.
 */
class TvSyncScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    interface Callback {
        /** The link of the QR code while pairing is open */
        val pairingLink: StateFlow<SyncLink?>
        val phones: Flow<List<PairedDevice>>
        val canUndoBookmarks: Boolean
        fun startPairing()
        fun stopPairing()
        fun forget(phone: PairedDevice)
        fun undoBookmarks()
        fun onClosed()
    }

    private val vb: ViewTvSyncScreenBinding
    private lateinit var scope: CoroutineScope
    private lateinit var callback: Callback
    private val adapter = Adapter()
    private var jobs = emptyList<Job>()
    private var shownLink: SyncLink? = null

    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hide()
    }

    val isOpen: Boolean get() = isVisible

    init {
        orientation = VERTICAL
        visibility = GONE
        //clicks and the cursor do not reach the page under it
        isClickable = true
        setBackgroundColor(context.themeColor(R.attr.tbColorDsSurface))
        vb = ViewTvSyncScreenBinding.inflate(LayoutInflater.from(context), this)
        vb.rvPhones.layoutManager = LinearLayoutManager(context)
        vb.rvPhones.itemAnimator = null
        vb.rvPhones.adapter = adapter
        vb.rvPhones.addItemDecoration(ListDividerDecoration(vb.rvPhones, adapter, R.attr.tbColorDsLine, R.dimen.ds_border_hairline))
        vb.ibBack.setOnClickListener { hide() }
        vb.llUndoBookmarks.setOnClickListener { callback.undoBookmarks() }
    }

    fun attach(scope: CoroutineScope, callback: Callback) {
        this.scope = scope
        this.callback = callback
    }

    fun show() {
        if (isOpen) return
        visibility = VISIBLE
        backCallback.isEnabled = true
        shownLink = null
        vb.ivQr.setImageDrawable(null)
        vb.pbQr.isVisible = true
        refresh()
        callback.startPairing()
        jobs = listOf(
            scope.launch { callback.pairingLink.collect { renderLink(it) } },
            scope.launch { callback.phones.collect { renderPhones(it) } }
        )
        vb.ibBack.requestFocus()
    }

    fun hide() {
        if (!isOpen) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        callback.stopPairing()
        visibility = GONE
        backCallback.isEnabled = false
        callback.onClosed()
    }

    /** Undo may have become possible or impossible */
    fun refresh() {
        vb.llUndoBookmarks.isVisible = callback.canUndoBookmarks
    }

    private fun renderLink(link: SyncLink?) {
        if (link == shownLink) return
        shownLink = link
        vb.pbQr.isVisible = link == null
        vb.ivQr.setImageBitmap(link?.let { QrBitmap.of(it.toUrl()) })
        //the bitmap is drawn at the pixels of its modules: no blur
        (vb.ivQr.drawable as? android.graphics.drawable.BitmapDrawable)?.setFilterBitmap(false)
        vb.tvNetwork.text = when {
            link == null -> context.getString(R.string.sync_starting)
            link.addresses.all { it == "0.0.0.0" } -> context.getString(R.string.sync_no_network)
            else -> context.getString(R.string.sync_same_network, link.addresses.joinToString(", "))
        }
    }

    private fun renderPhones(phones: List<PairedDevice>) {
        val hadFocus = vb.rvPhones.hasFocus()
        adapter.submitList(phones) {
            if (hadFocus && phones.isEmpty()) vb.ibBack.requestFocus()
        }
        vb.tvNoPhones.isVisible = phones.isEmpty()
    }

    /** Keys move the focus only inside the screen, not to the bar under it */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!isOpen) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    private inner class Adapter : ListAdapter<PairedDevice, Holder>(object : DiffUtil.ItemCallback<PairedDevice>() {
        override fun areItemsTheSame(oldItem: PairedDevice, newItem: PairedDevice) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PairedDevice, newItem: PairedDevice) = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ViewTvPairedPhoneRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(val vb: ViewTvPairedPhoneRowBinding) : RecyclerView.ViewHolder(vb.root) {
        private var phone: PairedDevice? = null

        init {
            vb.llMain.setOnClickListener { phone?.let { callback.forget(it) } }
            //the focused row tells what OK does
            vb.llMain.setOnFocusChangeListener { _, focused -> vb.tvHint.isVisible = focused }
        }

        fun bind(phone: PairedDevice) {
            this.phone = phone
            vb.tvName.text = phone.name
            vb.tvCaption.text = context.getString(R.string.sync_last_seen,
                DateUtils.getRelativeTimeSpanString(phone.lastSeenAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        }
    }
}
