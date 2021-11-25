package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsVersionBinding
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity

class VersionSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private var vb = ViewSettingsVersionBinding.inflate(LayoutInflater.from(getContext()), this, true)
    var settingsModel: SettingsModel
    var callback: Callback? = null

    private val updateChannelSelectedListener: AdapterView.OnItemSelectedListener

    interface Callback {
        fun onNeedToCloseSettings()
    }

    init {
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)

        vb.tvVersion.text = context.getString(R.string.version_s, BuildConfig.VERSION_NAME)

        vb.tvLink.text = Html.fromHtml("<p><u>https://github.com/truefedex/tv-bro</u></p>")
        vb.tvLink.setOnClickListener {
            callback?.onNeedToCloseSettings()
            val intent = Intent(activity, MainActivity::class.java)
            intent.data = Uri.parse(vb.tvLink.text.toString())
            activity?.startActivity(intent)
        }

        vb.chkAutoCheckUpdates.isChecked = settingsModel.needAutockeckUpdates

        vb.chkAutoCheckUpdates.setOnCheckedChangeListener { buttonView, isChecked ->
            settingsModel.saveAutoCheckUpdates(isChecked)

            updateUIVisibility()
        }

        updateChannelSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val selectedChannel = settingsModel.updateChecker.versionCheckResult!!.availableChannels[position]
                if (selectedChannel == settingsModel.updateChannel) return
                settingsModel.saveUpdateChannel(selectedChannel)
                settingsModel.checkUpdate(true) {
                    updateUIVisibility()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
        vb.spUpdateChannel.onItemSelectedListener = updateChannelSelectedListener

        vb.btnUpdate.setOnClickListener {
            callback?.onNeedToCloseSettings()
            settingsModel.showUpdateDialogIfNeeded(activity as MainActivity, true)
        }

        updateUIVisibility()
    }

    private fun updateUIVisibility() {
        if (settingsModel.updateChecker.versionCheckResult == null) {
            settingsModel.checkUpdate(false) {
                if (settingsModel.updateChecker.versionCheckResult != null) {
                    updateUIVisibility()
                }
            }
            return
        }

        vb.tvUpdateChannel.visibility = if (settingsModel.needAutockeckUpdates) View.VISIBLE else View.INVISIBLE
        vb.spUpdateChannel.visibility = if (settingsModel.needAutockeckUpdates) View.VISIBLE else View.INVISIBLE

        if (settingsModel.needAutockeckUpdates) {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                    settingsModel.updateChecker.versionCheckResult!!.availableChannels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            vb.spUpdateChannel.adapter = adapter
            val selected = settingsModel.updateChecker.versionCheckResult!!.availableChannels.indexOf(settingsModel.updateChannel)
            if (selected != -1) {
                vb.spUpdateChannel.onItemSelectedListener = null
                vb.spUpdateChannel.setSelection(selected)
                vb.spUpdateChannel.onItemSelectedListener = updateChannelSelectedListener
            }
        }

        val hasUpdate = settingsModel.updateChecker.hasUpdate()
        vb.tvNewVersion.visibility = if (settingsModel.needAutockeckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        vb.btnUpdate.visibility = if (settingsModel.needAutockeckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        if (hasUpdate) {
            vb.tvNewVersion.text = context.getString(R.string.new_version_available_s, settingsModel.updateChecker.versionCheckResult!!.latestVersionName)
        }
    }
}