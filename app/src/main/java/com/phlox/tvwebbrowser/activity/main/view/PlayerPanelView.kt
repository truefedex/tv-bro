package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewPlayerPanelBinding
import com.phlox.tvwebbrowser.databinding.ViewPlayerPanelRowBinding
import com.phlox.tvwebbrowser.utils.themeColor

/**
 * A panel at the end of the screen over a video (the video settings of the built-in player, the choice of a stream):
 * the screen dimmed, and a column of its full height with the title and the caption of a [Page], its rows and, at the
 * bottom, what Back does. Pages go on a stack ([open], [push]); [back] goes to the page under, then closes the panel.
 * It is a view over the screen, not a window, so keys and Back keep going through the activity (the owner gives Back
 * to [back]); the focus stays in the panel while it is open, and [onClosed] tells the owner, which gives it back.
 */
class PlayerPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    /**
     * @param icon its glyph; for the options of a choice, [R.drawable.ic_check] on the [checked] one (the others keep
     *   its room empty), null for none
     * @param value the current choice of a row that opens more choices ([chevron])
     * @param caption a second line under the title
     * @param badge a tag after the title
     * @param checked the chosen option (selected)
     * @param dividerBefore a line before the row: the rows after it are of another kind
     */
    class Row(@DrawableRes val icon: Int?, val title: String, val value: String? = null, val caption: String? = null,
              val badge: String? = null, val chevron: Boolean = false, val checked: Boolean = false,
              val dividerBefore: Boolean = false, val action: () -> Unit)

    /** [footer]: what Back does ("Close", "To video settings"); [focus]: the row that gets the focus */
    class Page(val title: String, val caption: String?, val rows: List<Row>, val footer: String, val focus: Int = 0)

    private val vb = ViewPlayerPanelBinding.inflate(LayoutInflater.from(context), this)
    private val stack = ArrayList<Page>()

    /** The panel closed (Back on its first page, or [close]) */
    var onClosed: (() -> Unit)? = null

    val isOpen: Boolean get() = isVisible

    init {
        visibility = GONE
        setBackgroundColor(context.themeColor(R.attr.tbColorDsScrim))
        //the video under it gets no clicks of the cursor
        isClickable = true
    }

    /** Shows [page] as the first one */
    fun open(page: Page) {
        stack.clear()
        push(page)
    }

    /** Shows [page] over the current one; Back goes back to it */
    fun push(page: Page) {
        stack += page
        show(page)
    }

    /** The page under the current one, or closes the panel on the first page */
    fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            show(stack.last())
        } else {
            close()
        }
    }

    fun close() {
        if (!isOpen) return
        visibility = GONE
        stack.clear()
        vb.llRows.removeAllViews()
        onClosed?.invoke()
    }

    private fun show(page: Page) {
        visibility = VISIBLE
        vb.ivBack.isVisible = stack.size > 1
        vb.tvTitle.text = page.title
        vb.tvCaption.text = page.caption
        vb.tvCaption.isVisible = !page.caption.isNullOrEmpty()
        vb.tvFooter.text = page.footer
        vb.llRows.removeAllViews()
        val inflater = LayoutInflater.from(context)
        var focus: View? = null
        page.rows.forEachIndexed { i, row ->
            if (row.dividerBefore) addDivider()
            val rb = ViewPlayerPanelRowBinding.inflate(inflater, vb.llRows, true)
            if (row.icon != null) {
                rb.ivIcon.setImageResource(row.icon)
                rb.ivIcon.visibility = if (row.icon == R.drawable.ic_check && !row.checked) INVISIBLE else VISIBLE
            } else {
                rb.ivIcon.visibility = GONE
            }
            rb.tvTitle.text = row.title
            rb.tvBadge.text = row.badge
            rb.tvBadge.isVisible = row.badge != null
            rb.tvCaption.text = row.caption
            rb.tvCaption.isVisible = row.caption != null
            rb.tvValue.text = row.value
            rb.tvValue.isVisible = row.value != null
            rb.ivChevron.isVisible = row.chevron
            rb.root.isActivated = row.checked
            rb.root.setOnClickListener { row.action() }
            if (i == page.focus) focus = rb.root
        }
        vb.svRows.scrollTo(0, 0)
        (focus ?: vb.llRows.getChildAt(0))?.requestFocus()
    }

    private fun addDivider() {
        val divider = View(context)
        divider.setBackgroundColor(context.themeColor(R.attr.tbColorDsLine))
        vb.llRows.addView(divider)
        val margin = resources.getDimensionPixelSize(R.dimen.player_panel_divider_margin)
        val inset = resources.getDimensionPixelSize(R.dimen.player_panel_row_padding)
        divider.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = resources.getDimensionPixelSize(R.dimen.ds_border_hairline)
            setMargins(inset, margin, inset, margin)
        }
    }

    /** Keys move the focus only inside the panel: the search does not look at the views under it */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!isOpen) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }
}
