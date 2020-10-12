package com.phlox.tvwebbrowser.activity.main

import android.R.attr.host
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.AdBlockItem
import com.phlox.tvwebbrowser.model.AdItemType
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.SAXParserFactory


class AdblockViewModel(val app: Application) : AndroidViewModel(app) {
    companion object {
        val TAG = AdblockViewModel::class.java.simpleName
        const val ADBLOCK_ENABLED_PREF_KEY = "adblock_enabled"
        const val ADBLOCK_LAST_UPDATE_LIST_KEY = "adblock_last_update"

        const val ADBLOCK_MIN_LIST_UPDATE_INTERVAL_MS = 1000 * 60 * 60 * 24 * 7//weekly
        const val PREPACKED_LIST_XML_FILE = "adblockerlist.xml"
    }

    private var prefs = app.getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
    var adBlockEnabled: Boolean = true
        set(value) {
            field = value
            prefs.edit().putBoolean(ADBLOCK_ENABLED_PREF_KEY, field).apply()
        }
    val lastUpdate = Calendar.getInstance()

    init {
        adBlockEnabled = prefs.getBoolean(ADBLOCK_ENABLED_PREF_KEY, true)
        if (prefs.contains(ADBLOCK_LAST_UPDATE_LIST_KEY)) {
            val lastUpdateDate = Date(prefs.getLong(ADBLOCK_LAST_UPDATE_LIST_KEY, 0))
            lastUpdate.time = lastUpdateDate
        } else {
            loadPrepackagedList()
        }
    }

    @SuppressLint("SimpleDateFormat", "ApplySharedPref")
    private fun loadPrepackagedList() = viewModelScope.launch(Dispatchers.IO) {
        val parser = SAXParserFactory.newInstance().newSAXParser()
        val db = AppDatabase.db.adBlockList()
        db.deleteAll()
        val handler = object : DefaultHandler() {
            var item: AdBlockItem? = null

            @Throws(SAXException::class)
            override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes) {
                if (localName == "root") {
                    for (i in 0 until attributes.length) {
                        if (attributes.getLocalName(i) == "date") {
                            val date = SimpleDateFormat("dd-MM-yyyy HH:mm:ss.SSSZ")
                                    .parse(attributes.getValue(i))
                            date?.apply {
                                lastUpdate.time = this
                                prefs.edit().putLong(ADBLOCK_LAST_UPDATE_LIST_KEY, this.time).commit()
                            }
                        }
                    }
                } else if (localName == "item") {
                    var type = AdItemType.HOST
                    for (i in 0 until attributes.length) {
                        if (attributes.getLocalName(i) == "type") {
                            type = AdItemType.valueOf(attributes.getValue(i).toUpperCase(Locale.ROOT))
                        }
                    }
                    item = AdBlockItem(type)
                }
            }

            @Throws(SAXException::class)
            override fun endElement(uri: String?, localName: String, qName: String?) {
                item?.apply {
                    db.insert(this)
                    item = null
                }
            }

            @Throws(SAXException::class)
            override fun characters(ch: CharArray?, start: Int, length: Int) {
                item?.apply { value = String(ch!!, start, length) }
            }
        }
        try {
            app.assets.open(PREPACKED_LIST_XML_FILE).use { parser.parse(it, handler) }
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.recordException(e)
        }
    }

    fun isAd(url: Uri): Boolean {
        val host = url.host ?: return false
        if (TextUtils.isEmpty(host)) {
            return false
        }
        val db = AppDatabase.db.adBlockList()
        var parts = host.toLowerCase(Locale.ROOT).split('.')
        while (parts.size >= 2) {
            val hostNameToCheck = parts.joinToString(".")
            val found = db.findFirstHostThatMatches(hostNameToCheck)
            if (found.isNotEmpty()) return true
            parts = parts.drop(1)
        }
        return false
    }
}