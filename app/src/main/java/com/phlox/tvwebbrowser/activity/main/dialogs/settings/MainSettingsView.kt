package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebViewFeature
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.AdblockModel
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsMainBinding
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity
import java.text.SimpleDateFormat
import java.util.*

class MainSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private var vb: ViewSettingsMainBinding = ViewSettingsMainBinding.inflate(LayoutInflater.from(getContext()), this, true)
    var settingsModel: SettingsModel =
        ActiveModelsRepository.get(SettingsModel::class, activity!!)
    var adblockModel: AdblockModel = ActiveModelsRepository.get(AdblockModel::class, activity!!)

    init {
        initSearchEngineConfigUI()

        initUAStringConfigUI(context)

        initAdBlockConfigUI()

        initThemeSettingsUI()

        initKeepScreenOnUI()
    }

    private fun initThemeSettingsUI() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            vb.llThemeSettings.visibility = View.GONE
            return
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, context.resources.getStringArray(R.array.themes))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        vb.spTheme.adapter = adapter

        vb.spTheme.setSelection(settingsModel.theme.ordinal, false)

        vb.spTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (settingsModel.theme.ordinal == position) return
                settingsModel.theme = Config.Theme.values()[position]
                Toast.makeText(context, context.getString(R.string.need_restart), Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun initKeepScreenOnUI() {
        vb.scKeepScreenOn.isChecked = settingsModel.keepScreenOn

        vb.scKeepScreenOn.setOnCheckedChangeListener { buttonView, isChecked ->
            settingsModel.keepScreenOn = isChecked
        }
    }

    private fun initAdBlockConfigUI() {
        vb.scAdblock.isChecked = adblockModel.adBlockEnabled
        vb.scAdblock.setOnCheckedChangeListener { buttonView, isChecked ->
            adblockModel.adBlockEnabled = isChecked
            vb.llAdBlockerDetails.visibility = if (isChecked) VISIBLE else GONE
        }
        vb.llAdBlockerDetails.visibility = if (adblockModel.adBlockEnabled) VISIBLE else GONE

        adblockModel.clientLoading.subscribe(activity as FragmentActivity) {
            updateAdBlockInfo()
        }

        vb.btnAdBlockerUpdate.setOnClickListener {
            if (adblockModel.clientLoading.value) return@setOnClickListener
            adblockModel.loadAdBlockList(true)
            it.isEnabled = false
        }

        updateAdBlockInfo()
    }

    private fun updateAdBlockInfo() {
        val dateFormat = SimpleDateFormat("hh:mm dd MMMM yyyy", Locale.getDefault())
        val lastUpdate = if (adblockModel.lastUpdateListTime == 0L)
            context.getString(R.string.never) else
            dateFormat.format(Date(adblockModel.lastUpdateListTime))
        val infoText = "URL: ${adblockModel.adBlockListURL}\n${context.getString(R.string.last_update)}: $lastUpdate"
        vb.tvAdBlockerListInfo.text = infoText
        val loadingAdBlockList = adblockModel.clientLoading.value
        vb.btnAdBlockerUpdate.visibility = if (loadingAdBlockList) View.GONE else View.VISIBLE
        vb.pbAdBlockerListLoading.visibility = if (loadingAdBlockList) View.VISIBLE else View.GONE
    }

    private fun initUAStringConfigUI(context: Context) {
        val selected = if (settingsModel.uaString.value == "" ||
                settingsModel.uaString.value.startsWith(SettingsModel.TV_BRO_UA_PREFIX)) {
            0
        } else {
            settingsModel.uaStrings.indexOf(settingsModel.uaString.value ?: "")
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, settingsModel.userAgentStringTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vb.spTitles.adapter = adapter

        if (selected != -1) {
            vb.spTitles.setSelection(selected, false)
            vb.etUAString.setText(settingsModel.uaStrings[selected])
        } else {
            vb.spTitles.setSelection(settingsModel.userAgentStringTitles.size - 1, false)
            vb.llUAString.visibility = View.VISIBLE
            vb.etUAString.setText(settingsModel.uaString.value)
            vb.etUAString.requestFocus()
        }
        vb.spTitles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == settingsModel.userAgentStringTitles.size - 1 && vb.llUAString.visibility == View.GONE) {
                    vb.llUAString.visibility = View.VISIBLE
                    vb.llUAString.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    vb.etUAString.requestFocus()
                }
                vb.etUAString.setText(settingsModel.uaStrings[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
    }

    private fun initSearchEngineConfigUI() {
        var selected = 0
        if ("" != settingsModel.searchEngineURL.value) {
            selected = settingsModel.SearchEnginesURLs.indexOf(settingsModel.searchEngineURL.value)
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, settingsModel.SearchEnginesTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        vb.spEngine.adapter = adapter

        if (selected != -1) {
            vb.spEngine.setSelection(selected)
            vb.etUrl.setText(settingsModel.SearchEnginesURLs[selected])
        } else {
            vb.spEngine.setSelection(settingsModel.SearchEnginesTitles.size - 1)
            vb.llURL.visibility = View.VISIBLE
            vb.etUrl.setText(settingsModel.searchEngineURL.value)
            vb.etUrl.requestFocus()
        }
        vb.spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == settingsModel.SearchEnginesTitles.size - 1 && vb.llURL.visibility == View.GONE) {
                    vb.llURL.visibility = View.VISIBLE
                    vb.llURL.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    vb.etUrl.requestFocus()
                }
                vb.etUrl.setText(settingsModel.SearchEnginesURLs[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
        vb.scSearchEngineHomePage.isChecked = settingsModel.setSearchEngineAsHomePage
    }

    fun save() {
        val url = vb.etUrl.text.toString()
        settingsModel.changeSearchEngineUrl(url)

        val searchEngineIsHomePage = vb.scSearchEngineHomePage.isChecked
        settingsModel.setSearchEngineAsHomePage(searchEngineIsHomePage, url)

        val userAgent = vb.etUAString.text.toString().trim(' ')
        settingsModel.saveUAString(userAgent)
    }
}
