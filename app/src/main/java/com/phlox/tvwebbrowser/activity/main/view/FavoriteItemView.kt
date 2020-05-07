package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.FavoriteItem

/**
 * Created by PDT on 13.09.2016.
 */
class FavoriteItemView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        FrameLayout(context, attrs, defStyleAttr) {
    var favorite: FavoriteItem? = null
        private set

    private var ibDelete: ImageButton? = null
    private var tvTitle: TextView? = null
    private var tvUrl: TextView? = null
    private var ivArrow: ImageView? = null
    private var llContent: LinearLayout? = null
    var listener: Listener? = null

    interface Listener {
        fun onDeleteClick(favorite: FavoriteItem)
        fun onEditClick(favorite: FavoriteItem)
    }

    init {
        init()
    }

    private fun init() {
        LayoutInflater.from(context).inflate(R.layout.view_favorite_item, this)
        ibDelete = findViewById<View>(R.id.ibDelete) as ImageButton
        tvTitle = findViewById<View>(R.id.tvTitle) as TextView
        tvUrl = findViewById<View>(R.id.tvUrl) as TextView
        ivArrow = findViewById<View>(R.id.ivArrow) as ImageView
        llContent = findViewById<View>(R.id.llContent) as LinearLayout

        ibDelete!!.setOnClickListener { favorite?.let { listener?.onDeleteClick(it)} }

        llContent!!.setOnClickListener {  favorite?.let {listener?.onEditClick(it)} }
    }

    fun bind(favorite: FavoriteItem, editMode: Boolean) {
        this.favorite = favorite
        ibDelete!!.visibility = if (editMode) View.VISIBLE else View.GONE
        llContent!!.isClickable = editMode
        llContent!!.isFocusable = editMode
        ivArrow!!.visibility = if (favorite.isFolder) View.VISIBLE else View.GONE
        tvTitle!!.text = favorite.title
        tvUrl!!.text = favorite.url
    }
}
