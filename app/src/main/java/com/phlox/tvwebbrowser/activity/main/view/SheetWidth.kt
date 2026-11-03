package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.phlox.tvwebbrowser.R

/**
 * The width of a bottom sheet of the phone UI: the whole window on phones (also in landscape), at most
 * [R.dimen.ds_phone_sheet_max_width] in the middle of a wider window on tablets. A sheet that spans the
 * window keeps out of its side insets (the navigation bar, a cutout); a narrower one is clear of them.
 * It is decided while the sheet's parent measures, since the window changes size on rotation without
 * new views.
 */
internal class SheetWidth(private val context: Context) {
    private val maxWidth = context.resources.getDimensionPixelSize(R.dimen.ds_phone_sheet_max_width)
    var insetLeft = 0
    var insetRight = 0

    /**
     * Called by the parent's onMeasure before it measures [sheet]; [applyInsets] gets the side insets
     * the sheet has to keep out of.
     */
    fun fit(sheet: View, availableWidth: Int, applyInsets: (left: Int, right: Int) -> Unit) {
        val narrow = isTablet(context) && availableWidth - 2 * maxOf(insetLeft, insetRight) > maxWidth
        val width = if (narrow) maxWidth else ViewGroup.LayoutParams.MATCH_PARENT
        if (sheet.layoutParams.width != width) sheet.layoutParams.width = width
        if (narrow) applyInsets(0, 0) else applyInsets(insetLeft, insetRight)
    }
}

/** A screen of a tablet; phones in landscape are as wide, but not in their smallest width */
internal fun isTablet(context: Context): Boolean =
    context.resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP

private const val TABLET_SMALLEST_WIDTH_DP = 600

/** Sets the padding only when it changes, so a call from onMeasure does not ask for another layout */
internal fun View.setPaddingIfChanged(left: Int, top: Int, right: Int, bottom: Int) {
    if (paddingLeft != left || paddingTop != top || paddingRight != right || paddingBottom != bottom) {
        setPadding(left, top, right, bottom)
    }
}
