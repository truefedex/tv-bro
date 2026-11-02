package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.AddressSuggestions.Row
import com.phlox.tvwebbrowser.singleton.FaviconsPool
import com.phlox.tvwebbrowser.utils.themeColor
import kotlinx.coroutines.launch

/**
 * The list of the address suggestions ([com.phlox.tvwebbrowser.activity.main.AddressSuggestions]): under the
 * address of the TV bar, above the phone bar. Its rows are not focusable, the focus stays in the address
 * field: on TV the D-pad chooses a row from the field ([moveHighlight], drawn as selected), a tap or a click
 * opens one ([onOpen]). Hidden without rows.
 */
class AddressSuggestionsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    /** The layout of a row: the favicon (ivIcon), the title (tvTitle) and the address (tvCaption) */
    @LayoutRes
    var rowLayout = R.layout.view_address_suggestion_row
    /**
     * The best row is the last one, next to the bar under the list (phone); without room for all rows
     * the worst ones (at the top) are cut
     */
    var bestAtBottom = false
        set(value) {
            field = value
            gravity = if (value) Gravity.BOTTOM else Gravity.TOP
        }
    var onOpen: ((url: String) -> Unit)? = null
    /** Gives the favicon of a url (tests give none: the pool loads in a scope of its own) */
    var loadFavicon: suspend (url: String) -> Bitmap? = { FaviconsPool.get(it) }
    /** The chosen row changed (-1: none) */
    var onHighlightChanged: ((index: Int) -> Unit)? = null

    var rows: List<Row> = emptyList()
        private set
    /** The row chosen with the D-pad, in the order of [rows] (the best first); -1 when none */
    var highlighted = -1
        private set

    private val markColor = context.themeColor(R.attr.tbColorDsControlSelected)
    private val iconTint = ColorStateList.valueOf(context.themeColor(R.attr.tbColorDsIcon))
    private val holders = ArrayList<Holder>()

    private inner class Holder(val view: View) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val caption: TextView = view.findViewById(R.id.tvCaption)
        var url: String? = null

        init {
            view.setOnClickListener { url?.let { onOpen?.invoke(it) } }
        }

        fun bind(row: Row) {
            title.text = markWords(row.title, row.words, markColor)
            caption.text = markWords(row.address, row.words, markColor)
            if (url == row.url) return
            url = row.url
            ImageViewCompat.setImageTintList(icon, iconTint)
            icon.setImageResource(R.drawable.ic_not_available)
            val activity = context as? AppCompatActivity ?: return
            activity.lifecycleScope.launch {
                val favicon = loadFavicon(row.url) ?: return@launch
                //the row may show another url by now
                if (url != row.url) return@launch
                ImageViewCompat.setImageTintList(icon, null)
                icon.setImageBitmap(favicon)
            }
        }
    }

    init {
        orientation = VERTICAL
    }

    /**
     * Rows that do not fit are cut (at the bottom, or at the top for [bestAtBottom]), not squeezed: the
     * rows are measured with no limit, the list as tall as there is room for
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.AT_MOST) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val room = MeasureSpec.getSize(heightMeasureSpec)
        if (measuredHeight > room) setMeasuredDimension(measuredWidthAndState, room)
    }

    /** Shows the [rows], the best first (none: hidden); no row is chosen */
    fun render(rows: List<Row>) {
        this.rows = rows
        while (holders.size < rows.size) {
            val view = LayoutInflater.from(context).inflate(rowLayout, this, false)
            holders += Holder(view)
            addView(view)
        }
        while (holders.size > rows.size) {
            removeView(holders.removeAt(holders.size - 1).view)
        }
        for ((i, row) in rows.withIndex()) holders[holderIndex(i)].bind(row)
        visibility = if (rows.isEmpty()) GONE else VISIBLE
        setHighlight(-1)
    }

    /** The holder (in the order of the views) of the row at [index] (in the order of [rows]) */
    private fun holderIndex(index: Int) = if (bestAtBottom) rows.size - 1 - index else index

    /**
     * Chooses the next row ([down]) or the previous one; Up from the first one chooses none
     * @return false when there are no rows
     */
    fun moveHighlight(down: Boolean): Boolean {
        if (rows.isEmpty()) return false
        val next = if (down) minOf(highlighted + 1, rows.size - 1) else highlighted - 1
        setHighlight(maxOf(-1, next))
        return true
    }

    /** The url of the chosen row, null when none is chosen */
    val highlightedUrl: String? get() = rows.getOrNull(highlighted)?.url

    fun clearHighlight() = setHighlight(-1)

    private fun setHighlight(index: Int) {
        val changed = index != highlighted
        highlighted = index
        for ((i, holder) in holders.withIndex()) holder.view.isSelected = i == holderIndex(index) && index >= 0
        if (changed) onHighlightChanged?.invoke(index)
    }
}
