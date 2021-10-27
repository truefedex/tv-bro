package com.phlox.tvwebbrowser.activity.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application): AndroidViewModel(application) {
    val items = ObservableValue<List<Download>>(ArrayList())
    private var loading = false

    fun loadItems(offset: Long = 0) = viewModelScope.launch(Dispatchers.Main) {
        if (loading) {
            return@launch
        }
        loading = true

        items.value = AppDatabase.db.downloadDao().allByLimitOffset(offset)

        loading = false
    }
}