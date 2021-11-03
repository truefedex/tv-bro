package com.phlox.tvwebbrowser.activity.downloads

import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadsHistoryModel: ActiveModel() {
  val allItems = ArrayList<Download>()
  val lastLoadedItems = ObservableValue<List<Download>>(ArrayList())
  private var loading = false

  fun loadNextItems() = modelScope.launch(Dispatchers.Main) {
    if (loading) {
      return@launch
    }
    loading = true

    val newItems = AppDatabase.db.downloadDao().allByLimitOffset(allItems.size.toLong())
    lastLoadedItems.value = newItems
    allItems.addAll(newItems)

    loading = false
  }
}