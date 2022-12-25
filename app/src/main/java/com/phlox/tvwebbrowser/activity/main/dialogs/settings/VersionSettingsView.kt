package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.annotation.SuppressLint
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
import androidx.webkit.WebViewCompat
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.databinding.ViewSettingsVersionBinding
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.utils.activity

@SuppressLint("SetTextI18n")
class VersionSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private var vb = ViewSettingsVersionBinding.inflate(LayoutInflater.from(getContext()), this, true)
    var settingsModel = ActiveModelsRepository.get(SettingsModel::class, activity!!)
    var callback: Callback? = null

    interface Callback {
        fun onNeedToCloseSettings()
    }

    init {
        vb.tvVersion.text = context.getString(R.string.version_s, BuildConfig.VERSION_NAME)

        val webViewPackage = WebViewCompat.getCurrentWebViewPackage(context)
        val webViewVersion = (webViewPackage?.packageName ?: "unknown") + ":" + (webViewPackage?.versionName ?: "unknown")
        vb.tvWebViewVersion.text = webViewVersion

        vb.tvLink.text = Html.fromHtml("<p><u>https://github.com/truefedex/tv-bro</u></p>")
        vb.tvLink.setOnClickListener {
            loadUrl(vb.tvLink.text.toString())
        }

        vb.tvUkraine.setOnClickListener {
            loadUrl("https://tv-bro-3546c.web.app/msg001.html")
        }

        if (BuildConfig.BUILT_IN_AUTO_UPDATE) {
            vb.chkAutoCheckUpdates.isChecked = settingsModel.needAutoCheckUpdates

            vb.chkAutoCheckUpdates.setOnCheckedChangeListener { buttonView, isChecked ->
                settingsModel.saveAutoCheckUpdates(isChecked)

                updateUIVisibility()
            }

            vb.spUpdateChannel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View,
                    position: Int,
                    id: Long
                ) {
                    val selectedChannel =
                        settingsModel.updateChecker.versionCheckResult!!.availableChannels[position]
                    if (selectedChannel == settingsModel.updateChannel) return
                    settingsModel.saveUpdateChannel(selectedChannel)
                    settingsModel.checkUpdate(true) {
                        updateUIVisibility()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {

                }
            }

            vb.btnUpdate.setOnClickListener {
                callback?.onNeedToCloseSettings()
                settingsModel.showUpdateDialogIfNeeded(activity as MainActivity, true)
            }

            updateUIVisibility()
        } else {
            vb.chkAutoCheckUpdates.visibility = View.INVISIBLE
        }
    }

    private fun loadUrl(url: String) {
        callback?.onNeedToCloseSettings()
        val activityClass = if (settingsModel.config.incognitoMode)
            IncognitoModeMainActivity::class.java else MainActivity::class.java
        val intent = Intent(activity, activityClass)
        intent.data = Uri.parse(url)
        activity?.startActivity(intent)
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

        vb.tvUpdateChannel.visibility = if (settingsModel.needAutoCheckUpdates) View.VISIBLE else View.INVISIBLE
        vb.spUpdateChannel.visibility = if (settingsModel.needAutoCheckUpdates) View.VISIBLE else View.INVISIBLE

        if (settingsModel.needAutoCheckUpdates) {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                    settingsModel.updateChecker.versionCheckResult!!.availableChannels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            vb.spUpdateChannel.adapter = adapter
            val selected = settingsModel.updateChecker.versionCheckResult!!.availableChannels.indexOf(settingsModel.updateChannel)
            if (selected != -1) {
                val tmp = vb.spUpdateChannel.onItemSelectedListener
                vb.spUpdateChannel.onItemSelectedListener = null
                vb.spUpdateChannel.setSelection(selected)
                vb.spUpdateChannel.onItemSelectedListener = tmp
            }
        }

        val hasUpdate = settingsModel.updateChecker.hasUpdate()
        vb.tvNewVersion.visibility = if (settingsModel.needAutoCheckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        vb.btnUpdate.visibility = if (settingsModel.needAutoCheckUpdates && hasUpdate) View.VISIBLE else View.INVISIBLE
        if (hasUpdate) {
            vb.tvNewVersion.text = context.getString(R.string.new_version_available_s, settingsModel.updateChecker.versionCheckResult!!.latestVersionName)
        }
    }
}