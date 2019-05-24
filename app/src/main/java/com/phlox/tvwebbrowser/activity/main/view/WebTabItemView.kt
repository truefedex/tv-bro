package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.WebTabState
import kotlinx.android.synthetic.main.view_tab_item.view.*

/**
 * Created by PDT on 24.08.2016.
 */
class WebTabItemView(context: Context) : FrameLayout(context) {
    private var tabState: WebTabState? = null
    private var listener: Listener? = null

    interface Listener {
        fun onTabSelected(tab: WebTabState)
        fun onTabDeleteClicked(tab: WebTabState)
        fun onNeededThumbnailSizeCalculated(width: Int, height: Int)
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_tab_item, this)

        llContainer.setOnClickListener { listener!!.onTabSelected(tabState!!) }
        btnClose.setOnClickListener { listener!!.onTabDeleteClicked(tabState!!) }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun bindTabState(tabState: WebTabState) {
        this.tabState = tabState
        tvTitle.text = if (TextUtils.isEmpty(tabState.currentTitle))
            Uri.parse(tabState.currentOriginalUrl).host
        else
            tabState.currentTitle
        if (tabState.thumbnail != null) {
            ivThumbnail.setImageBitmap(tabState.thumbnail)
        } else {
            ivThumbnail.setImageResource(android.R.color.transparent)
        }
        llContainer.isActivated = tabState.selected
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val calculatedHeightSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) * 4 / 5, View.MeasureSpec.getMode(widthMeasureSpec))
        super.onMeasure(widthMeasureSpec, calculatedHeightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        listener!!.onNeededThumbnailSizeCalculated(ivThumbnail.width, ivThumbnail.height)
    }
}
