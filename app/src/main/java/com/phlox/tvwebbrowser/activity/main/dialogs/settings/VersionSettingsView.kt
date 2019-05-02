package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.support.v4.app.FragmentActivity
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.utils.activity
import kotlinx.android.synthetic.main.view_settings_version.view.*

class VersionSettingsView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr) {
    var settingsViewModel: SettingsViewModel

    init {
        LayoutInflater.from(getContext()).inflate(R.layout.view_settings_version, this, true)
        settingsViewModel = ViewModelProviders.of(activity as FragmentActivity).get(SettingsViewModel::class.java)

        tvVersion.text = context.getString(R.string.version_s, BuildConfig.VERSION_NAME)
    }
}