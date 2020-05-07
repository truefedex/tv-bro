package com.phlox.tvwebbrowser.activity.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application): AndroidViewModel(application) {
    val items = MutableLiveData<List<HistoryItem>>().apply { ArrayList<HistoryItem>() }
    private var loading = false
    var searchQuery = ""


    fun loadItems(eraseOldResults: Boolean, offset: Long = 0) = viewModelScope.launch(Dispatchers.Main) {
        if (loading) {
            return@launch
        }
        loading = true

        items.value = if ("" == searchQuery) {
            AppDatabase.db.historyDao().allByLimitOffset(offset)
        } else {
            AppDatabase.db.historyDao().search(searchQuery, searchQuery)
        }
        loading = false
    }
}