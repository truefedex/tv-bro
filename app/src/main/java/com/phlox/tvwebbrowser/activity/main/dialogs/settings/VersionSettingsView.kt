package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import androidx.fragment.app.FragmentActivity
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsViewModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsVersionBinding
import com.phlox.tvwebbrowser.utils.activity

class VersionSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private var vb = ViewSettingsVersionBinding.inflate(LayoutInflater.from(getContext()), this, true)
    var settingsViewModel: SettingsViewModel
    var callback: Callback? = null

    private val updateChannelSelectedListener: AdapterView.OnItemSelectedListener

    interface Callback {
        fun onNeedToCloseSettings()
    }

    init {
        settingsViewModel = ViewModelProvider(activity as FragmentActivity).get(SettingsViewModel::class.java)

        vb.tvVersion.text = context.getString(R.string.version_s, BuildConfig.VERSION_NAME)

        vb.tvLink.text = Html.fromHtml("<p><u>https://github.com/truefedex/tv-bro</u></p>")
        vb.tvLink.setOnClickListener {
            callback?.onNeedToCloseSettings()
            val intent = Intent(activity, MainActivity::class.java)
            intent.data = Uri.parse(vb.tvLink.text.toString())
            activity?.startActivity(intent)
        }

        vb.chkAutoCheckUpdates.isChecked = settingsViewModel.needAutockeckUpdates

        vb.chkAutoCheckUpdates.setOnCheckedChangeListener { buttonView, isChecked ->
            settingsViewModel.saveAutoCheckUpdates(isChecked)

            updateUIVisibility()
        }

        updateChannelSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val selectedChannel = settingsViewModel.updateChecker.versionCheckResult!!.availableChannels[position]
                if (selectedChannel == settingsViewModel.updateChannel) return
                settingsViewModel.saveUpdateChannel(selectedChannel)
                settingsViewModel.checkUpdate(true) {
                    updateUIVisibility()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
        vb.spUpdateChannel.onItemSelectedListener = updateChannelSelectedListener

        vb.btnUpdate.setOnClickListener {
            callback?.onNeedToCloseSettings()
            settingsViewModel.showUpdateDialogIfNeeded(activity as MainActivity, true)
        }

        updateUIVisibility()
    }

    private fun updateUIVisibility() {
        if (settingsViewModel.updateChecker.versionCheckResult == null) {
            settingsViewModel.checkUpdate(false) {
                if (settingsViewModel.updateChecker.versionCheckResult != null) {
                    updateUIVisibility()
                }
            }
            return
        }

        vb.tvUpdateChannel.visibility = if (settingsViewModel.needAutockeckUpdates) View.VISIBLE else View.INVISIBLE
        vb.spUpdateChannel.visibility = if (settingsViewModel.needAutockeckUpdates) View.VISIBLE else View.INVISIBLE

        if (settingsViewModel.needAutockeckUpdates) {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                    settingsViewModel.updateChecker.versionCheckResult!!.availableChannels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            vb.spUpdateChannel.adapter = adapter
            val selected = settingsViewModel.updateChecker.versionCheckResult!!.availableChannels.indexOf(settingsViewModel.updateChannel)
            if (selected != -1) {
                vb.spUpdateChannel.onItemSelectedListener = null
                vb.spUpdateChannel.setSelection(selected)
                vb.spUpdateChannel.onItemSelectedListener = updateChannelSelectedListener
            }
        }

        val hasUpdate = settingsViewModel.updateChecker.hasUpdate()
        vb.tvNewVersion.visibility = if (settingsViewModel.needAutockeckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        vb.btnUpdate.visibility = if (settingsViewModel.needAutockeckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        if (hasUpdate) {
            vb.tvNewVersion.text = context.getString(R.string.new_version_available_s, settingsViewModel.updateChecker.versionCheckResult!!.latestVersionName)
        }
    }
}