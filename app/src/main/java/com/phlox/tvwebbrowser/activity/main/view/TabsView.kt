package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.TabsModel
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelUser
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository

class TabsView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : RecyclerView(context, attrs), ActiveModelUser {

  interface Listener {
    fun onTitleChanged(index: Int)
    fun onTitleSelected(index: Int)
    fun onTitleOptions(index: Int)
  }

  var listener: Listener? = null
  var current: Int = 0
    set(value) {
      field = value
      postInvalidate()
    }

  private lateinit var tabsModel: TabsModel
  private val emptyTitle: String

  init {
    ActiveModelsRepository.get(TabsModel::class, this)

    emptyTitle = context.getString(R.string.new_tab_title)
  }

  protected fun finalize() {
    ActiveModelsRepository.markAsNeedless(tabsModel,this)
  }
}