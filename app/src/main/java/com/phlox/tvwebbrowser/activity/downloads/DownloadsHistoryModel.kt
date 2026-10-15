package com.phlox.tvwebbrowser.activity.downloads

import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadsHistoryModel: ActiveModel() {
  val allItems = ArrayList<Download>()
  val lastLoadedItems = ObservableValue<List<Download>>(ArrayList())
  private var loading = false
  private var endReached = false

  fun loadNextItems() = modelScope.launch(Dispatchers.Main) {
    if (loading || endReached) {
      return@launch
    }
    loading = true

    val newItems = TVBro.container.data.downloads.page(allItems.size.toLong())
    if (newItems.isEmpty()) endReached = true
    allItems.addAll(newItems)
    lastLoadedItems.value = newItems

    loading = false
  }

  /** Forgets a deleted download, so the next page starts at the right offset */
  fun remove(download: Download) {
    allItems.remove(download)
  }
}