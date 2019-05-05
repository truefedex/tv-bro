package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.support.v4.app.FragmentActivity
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activity
import kotlinx.android.synthetic.main.view_settings_version.view.*

class VersionSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    var settingsViewModel: SettingsViewModel
    var callback: Callback? = null

    private val updateChannelSelectedListener: AdapterView.OnItemSelectedListener

    interface Callback {
        fun onNeedToCloseSettings()
    }

    init {
        LayoutInflater.from(getContext()).inflate(R.layout.view_settings_version, this, true)
        settingsViewModel = ViewModelProviders.of(activity as FragmentActivity).get(SettingsViewModel::class.java)

        tvVersion.text = context.getString(R.string.version_s, BuildConfig.VERSION_NAME)

        chkAutoCheckUpdates.isChecked = settingsViewModel.needAutockeckUpdates

        chkAutoCheckUpdates.setOnCheckedChangeListener { buttonView, isChecked ->
            settingsViewModel.saveAutoCheckUpdates(isChecked)

            updateUIVisibility()
        }

        updateChannelSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val selectedChannel = settingsViewModel.updateChecker.versionCheckResult!!.availableChannels[position]
                if (selectedChannel == settingsViewModel.updateChannel) return
                settingsViewModel.saveUpdateChannel(selectedChannel)
                settingsViewModel.checkUpdate {
                    updateUIVisibility()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
        spUpdateChannel.onItemSelectedListener = updateChannelSelectedListener

        btnUpdate.setOnClickListener {
            callback?.onNeedToCloseSettings()
            settingsViewModel.showUpdateDialogIfNeeded(activity as MainActivity, true)
        }

        updateUIVisibility()
    }

    private fun updateUIVisibility() {
        if (settingsViewModel.updateChecker.versionCheckResult == null) {
            settingsViewModel.checkUpdate {
                if (settingsViewModel.updateChecker.versionCheckResult != null) {
                    updateUIVisibility()
                }
            }
            return
        }

        tvUpdateChannel.visibility = if (settingsViewModel.needAutockeckUpdates) View.VISIBLE else View.INVISIBLE
        spUpdateChannel.visibility = if (settingsViewModel.needAutockeckUpdates) View.VISIBLE else View.INVISIBLE

        if (settingsViewModel.needAutockeckUpdates) {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                    settingsViewModel.updateChecker.versionCheckResult!!.availableChannels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spUpdateChannel.adapter = adapter
            val selected = settingsViewModel.updateChecker.versionCheckResult!!.availableChannels.indexOf(settingsViewModel.updateChannel)
            if (selected != -1) {
                spUpdateChannel.onItemSelectedListener = null
                spUpdateChannel.setSelection(selected)
                spUpdateChannel.onItemSelectedListener = updateChannelSelectedListener
            }
        }

        val hasUpdate = settingsViewModel.updateChecker.hasUpdate()
        tvNewVersion.visibility = if (settingsViewModel.needAutockeckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        btnUpdate.visibility = if (settingsViewModel.needAutockeckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        if (hasUpdate) {
            tvNewVersion.text = context.getString(R.string.new_version_available_s, settingsViewModel.updateChecker.versionCheckResult!!.latestVersionName)
        }
    }
}