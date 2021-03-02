package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.phlox.tvwebbrowser.activity.main.AdblockViewModel
import com.phlox.tvwebbrowser.activity.main.SettingsViewModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsMainBinding
import com.phlox.tvwebbrowser.utils.activity

class MainSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private var vb: ViewSettingsMainBinding = ViewSettingsMainBinding.inflate(LayoutInflater.from(getContext()), this)
    var settingsViewModel: SettingsViewModel
    var adblockViewModel: AdblockViewModel

    init {
        settingsViewModel = ViewModelProvider(activity as FragmentActivity).get(SettingsViewModel::class.java)
        adblockViewModel = ViewModelProvider(activity as FragmentActivity).get(AdblockViewModel::class.java)
        orientation = VERTICAL

        initSearchEngineConfigUI()

        initUAStringConfigUI(context)

        vb.scAdblock.isChecked = adblockViewModel.adBlockEnabled
        vb.scAdblock.setOnCheckedChangeListener { buttonView, isChecked ->
            adblockViewModel.adBlockEnabled = isChecked
        }
    }

    private fun initUAStringConfigUI(context: Context) {
        val selected = if (settingsViewModel.uaString.value == null || settingsViewModel.uaString.value == "" ||
                settingsViewModel.uaString.value!!.startsWith(SettingsViewModel.TV_BRO_UA_PREFIX)) {
            0
        } else {
            settingsViewModel.uaStrings.indexOf(settingsViewModel.uaString.value ?: "")
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, settingsViewModel.userAgentStringTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vb.spTitles.adapter = adapter

        if (selected != -1) {
            vb.spTitles.setSelection(selected, false)
            vb.etUAString.setText(settingsViewModel.uaStrings[selected])
        } else {
            vb.spTitles.setSelection(settingsViewModel.userAgentStringTitles.size - 1, false)
            vb.llUAString.visibility = View.VISIBLE
            vb.etUAString.setText(settingsViewModel.uaString.value)
            vb.etUAString.requestFocus()
        }
        vb.spTitles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == settingsViewModel.userAgentStringTitles.size - 1 && vb.llUAString.visibility == View.GONE) {
                    vb.llUAString.visibility = View.VISIBLE
                    vb.llUAString.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    vb.etUAString.requestFocus()
                }
                vb.etUAString.setText(settingsViewModel.uaStrings[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
    }

    private fun initSearchEngineConfigUI() {
        var selected = 0
        if ("" != settingsViewModel.searchEngineURL.value) {
            selected = settingsViewModel.SearchEnginesURLs.indexOf(settingsViewModel.searchEngineURL.value)
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, settingsViewModel.SearchEnginesTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        vb.spEngine.adapter = adapter

        if (selected != -1) {
            vb.spEngine.setSelection(selected)
            vb.etUrl.setText(settingsViewModel.SearchEnginesURLs[selected])
        } else {
            vb.spEngine.setSelection(settingsViewModel.SearchEnginesTitles.size - 1)
            vb.llURL.visibility = View.VISIBLE
            vb.etUrl.setText(settingsViewModel.searchEngineURL.value)
            vb.etUrl.requestFocus()
        }
        vb.spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == settingsViewModel.SearchEnginesTitles.size - 1 && vb.llURL.visibility == View.GONE) {
                    vb.llURL.visibility = View.VISIBLE
                    vb.llURL.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    vb.etUrl.requestFocus()
                }
                vb.etUrl.setText(settingsViewModel.SearchEnginesURLs[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
    }

    fun save() {
        val url = vb.etUrl.text.toString()
        settingsViewModel.changeSearchEngineUrl(url)

        val userAgent = vb.etUAString.text.toString().trim(' ')
        settingsViewModel.saveUAString(userAgent)
    }
}