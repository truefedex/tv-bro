package com.phlox.tvwebbrowser.widgets

import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import com.fedir.segmentedbutton.SegmentedButton

abstract class SegmentedButtonTabsAdapter(val segmentedButton: SegmentedButton, val contentLayout: ViewGroup) {
    var currentContentView: View? = null
        private  set
    private val contentViewsCache = SparseArray<View>()
    var callback: Callback? = null

    interface Callback {
        fun onCheckedChanged(button: SegmentedButton, checkedButtonId: Int, byUser: Boolean)
    }

    private val segmentedButtonCheckedChangeListener = SegmentedButton.OnCheckedChangeListener { button, checkedButtonId, byUser ->
        showTab(checkedButtonId)
        callback?.onCheckedChanged(button, checkedButtonId, byUser)
    }

    init {
        segmentedButton.checkedChangeListener = segmentedButtonCheckedChangeListener
        showTab(segmentedButton.checkedId)
    }

    private fun showTab(checkedSegmentId: Int) {
        if (checkedSegmentId == SegmentedButton.NO_ID) return
        contentLayout.removeAllViews()
        var view = contentViewsCache.get(checkedSegmentId)
        if (view == null) {
            view = createContentViewForSegmentButtonId(checkedSegmentId)
            contentViewsCache.put(checkedSegmentId, view)
        }
        contentLayout.addView(view)
        currentContentView = view
    }

    abstract fun createContentViewForSegmentButtonId(id: Int): View
}