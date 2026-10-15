package com.phlox.tvwebbrowser.activity.history

import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HistoryModel: ActiveModel() {
    val lastLoadedItems = ObservableValue<List<HistoryItem>>(ArrayList())
    private var loading = false
    private var endReached = false
    var searchQuery = ""
    private val history = TVBro.container.data.history


    fun loadItems(eraseOldResults: Boolean, offset: Long = 0) = modelScope.launch(Dispatchers.Main) {
        if (loading || (offset > 0 && endReached)) {
            return@launch
        }
        loading = true
        if (offset == 0L) endReached = false

        val items = if ("" == searchQuery) {
            history.page(offset)
        } else {
            history.search(searchQuery)
        }
        if (items.isEmpty()) endReached = true
        lastLoadedItems.value = items
        loading = false
    }
}