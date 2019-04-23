package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Context
import android.content.SharedPreferences
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.MainActivity
import java.util.*

class SettingsViewModel: ViewModel() {
    companion object {
        const val SEARCH_ENGINE_URL_PREF_KEY = "search_engine_url"
        const val USER_AGENT_PREF_KEY = "user_agent"
        const val TV_BRO_UA_PREFIX = "TV Bro/1.0 "
    }

    private var prefs: SharedPreferences
    val SearchEnginesTitles = arrayOf("Google", "Bing", "Yahoo!", "DuckDuckGo", "Yandex", "Custom")
    val SearchEnginesURLs = Arrays.asList("https://www.google.com/search?q=[query]", "https://www.bing.com/search?q=[query]",
            "https://search.yahoo.com/search?p=[query]", "https://duckduckgo.com/?q=[query]",
            "https://yandex.com/search/?text=[query]", "")
    var searchEngineURL = MutableLiveData<String>()

    val titles = arrayOf("TV Bro", "Chrome (Desktop)", "Chrome (Mobile)", "Chrome (Tablet)", "Firefox (Desktop)", "Firefox (Tablet)", "Edge (Desktop)", "Safari (Desktop)", "Safari (iPad)", "Apple TV", "Custom")
    val uaStrings = Arrays.asList("",
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.87 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 6.0.1; TV Build/DDD00D) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.98 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 7.0; TV Build/DDD00D; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/52.0.2743.98 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:50.0) Gecko/20100101 Firefox/50.0",
            "Mozilla/5.0 (Android 4.4; Tablet; rv:41.0) Gecko/41.0 Firefox/41.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/42.0.2311.135 Safari/537.36 Edge/12.246",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_2) AppleWebKit/601.3.9 (KHTML, like Gecko) Version/9.0.2 Safari/601.3.9",
            "Mozilla/5.0 (iPad; CPU OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53",
            "AppleTV5,3/9.1.1",
            "")
    var uaString = MutableLiveData<String>()

    init {
        prefs = TVBro.instance.getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        searchEngineURL.postValue(prefs.getString(SEARCH_ENGINE_URL_PREF_KEY, ""))
        uaString.postValue(prefs.getString(USER_AGENT_PREF_KEY, ""))
    }

    fun changeSearchEngineUrl(url: String) {
        val editor = prefs.edit()
        editor.putString(SEARCH_ENGINE_URL_PREF_KEY, url)
        editor.apply()
        searchEngineURL.value = url
    }

    fun saveUAString(uas: String) {
        val editor = prefs.edit()
        editor.putString(USER_AGENT_PREF_KEY, uas)
        editor.apply()
        uaString.postValue(uas)
    }
}