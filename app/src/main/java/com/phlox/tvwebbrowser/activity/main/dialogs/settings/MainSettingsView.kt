package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.support.v4.app.FragmentActivity
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.activity
import kotlinx.android.synthetic.main.view_settings_main.view.*

class MainSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    var settingsViewModel: SettingsViewModel

    init {
        LayoutInflater.from(getContext()).inflate(R.layout.view_settings_main, this, true)
        settingsViewModel = ViewModelProviders.of(activity as FragmentActivity).get(SettingsViewModel::class.java)
        orientation = VERTICAL

        initSearchEngineConfigUI()

        initUAStringConfigUI(context)
    }

    private fun initUAStringConfigUI(context: Context) {
        val selected = if (settingsViewModel.uaString.value == null || settingsViewModel.uaString.value == "" ||
                settingsViewModel.uaString.value!!.startsWith(SettingsViewModel.TV_BRO_UA_PREFIX)) {
            0
        } else {
            settingsViewModel.uaStrings.indexOf(settingsViewModel.uaString.value)
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, settingsViewModel.titles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spTitles.adapter = adapter

        if (selected != -1) {
            spTitles.setSelection(selected, false)
            etUAString.setText(settingsViewModel.uaStrings[selected])
        } else {
            spTitles.setSelection(settingsViewModel.titles.size - 1, false)
            llUAString.visibility = View.VISIBLE
            etUAString.setText(settingsViewModel.uaString.value)
            etUAString.requestFocus()
        }
        spTitles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == settingsViewModel.titles.size - 1 && llUAString.visibility == View.GONE) {
                    llUAString.visibility = View.VISIBLE
                    llUAString.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    etUAString.requestFocus()
                }
                etUAString.setText(settingsViewModel.uaStrings[position])
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

        spEngine.adapter = adapter

        if (selected != -1) {
            spEngine.setSelection(selected)
            etUrl.setText(settingsViewModel.SearchEnginesURLs[selected])
        } else {
            spEngine.setSelection(settingsViewModel.SearchEnginesTitles.size - 1)
            llURL.visibility = View.VISIBLE
            etUrl.setText(settingsViewModel.searchEngineURL.value)
            etUrl.requestFocus()
        }
        spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == settingsViewModel.SearchEnginesTitles.size - 1 && llURL.visibility == View.GONE) {
                    llURL.visibility = View.VISIBLE
                    llURL.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    etUrl.requestFocus()
                }
                etUrl.setText(settingsViewModel.SearchEnginesURLs[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
    }

    fun save() {
        val url = etUrl.text.toString()
        settingsViewModel.changeSearchEngineUrl(url)

        val userAgent = etUAString.text.toString().trim(' ')
        settingsViewModel.saveUAString(userAgent)
    }
}