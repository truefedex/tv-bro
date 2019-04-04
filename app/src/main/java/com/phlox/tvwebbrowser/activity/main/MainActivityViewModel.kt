package com.phlox.tvwebbrowser.activity.main

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Context
import android.util.Log
import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx
import com.phlox.tvwebbrowser.model.AndroidJSInterface
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.utils.StringUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class MainActivityViewModel: ViewModel() {
    companion object {
        const val STATE_JSON = "state.json"
    }

    val asql by lazy { ASQL.getDefault(TVBro.instance) }
    val currentTab = MutableLiveData<WebTabState>()
    val tabsStates = ArrayList<WebTabState>()
    var lastHistoryItem: HistoryItem? = null
    val jsInterface = AndroidJSInterface()

    fun saveState() {
        //WebTabState.saveTabs(TVBro.instance, tabsStates)
        val tabsCopy = tabsStates.map { it.copy() }//clone list

        GlobalScope.launch(Dispatchers.IO) {
            val store = JSONObject()
            val tabsStore = JSONArray()
            for (tab in tabsCopy) {
                val tabJson = tab.toJson(TVBro.instance, true)
                tabsStore.put(tabJson)
            }
            try {
                store.put("tabs", tabsStore)
                val fos = TVBro.instance.openFileOutput(STATE_JSON, Context.MODE_PRIVATE)
                try {
                    fos.write(store.toString().toByteArray())
                } finally {
                    fos.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun loadState() = GlobalScope.launch(Dispatchers.Main) {
        launch (Dispatchers.IO) { initHistory() }
        val tabsStatesLoaded = async (Dispatchers.IO){
            val tabsStates = ArrayList<WebTabState>()
            try {
                val fis = TVBro.instance.openFileInput(STATE_JSON)
                val storeStr = StringUtils.streamToString(fis)
                val store = JSONObject(storeStr)
                val tabsStore = store.getJSONArray("tabs")
                for (i in 0 until tabsStore.length()) {
                    val tab = WebTabState(TVBro.instance, tabsStore.getJSONObject(i))
                    tabsStates.add(tab)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            tabsStates
        }.await()

        tabsStates.addAll(tabsStatesLoaded)
    }

    private fun initHistory() {
        val count = asql.count(HistoryItem::class.java)
        if (count > 5000) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -3)
            asql.db.delete("history", "time < ?", arrayOf(java.lang.Long.toString(c.time.time)))
        }
        try {
            val result = asql.queryAll<HistoryItem>(HistoryItem::class.java, "SELECT * FROM history ORDER BY time DESC LIMIT 1")
            if (!result.isEmpty()) {
                lastHistoryItem = result.get(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val frequentlyUsedUrls = asql.queryAll<HistoryItem>(HistoryItem::class.java,
                    "SELECT title, url, favicon, count(url) as cnt , max(time) as time FROM history GROUP BY title, url, favicon ORDER BY cnt DESC, time DESC LIMIT 6")
            jsInterface.setSuggestions(TVBro.instance, frequentlyUsedUrls)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logVisitedHistory(title: String?, url: String?, faviconHash: String?) {
        if (url != null && (lastHistoryItem != null && url == lastHistoryItem!!.url || url == WebViewEx.HOME_URL)) {
            return
        }

        val item = HistoryItem()
        item.url = url
        item.title = title ?: ""
        item.time = Date().time
        item.favicon = faviconHash
        lastHistoryItem = item
        asql.execInsert("INSERT INTO history (time, title, url, favicon) VALUES (:time, :title, :url, :favicon)", lastHistoryItem) { lastInsertRowId, exception ->
            exception?.printStackTrace()
        }
    }
}