package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.view.View
import com.phlox.tvwebbrowser.R
import android.widget.TabHost

class SettingsDialog(context: Context, val viewModel: SettingsViewModel) : Dialog(context), DialogInterface.OnDismissListener {
    override fun onDismiss(dialog: DialogInterface?) {
        mainView.save()
    }

    private var mainView: MainSettingsView
    private var tabHost: TabHost

    init {
        setTitle(R.string.settings)
        setContentView(R.layout.dialog_settings)

        tabHost = findViewById(R.id.tabHost)

        tabHost.setup()

        val mainTabSpec = tabHost.newTabSpec("general")
        mainTabSpec.setIndicator(context.getString(R.string.main))
        mainTabSpec.setContent(R.id.tabMain)
        tabHost.addTab(mainTabSpec)

        val shortcutsTabSpec = tabHost.newTabSpec("shortcuts")
        shortcutsTabSpec.setIndicator(context.getString(R.string.shortcuts))
        shortcutsTabSpec.setContent(R.id.tabShortcuts)
        tabHost.addTab(shortcutsTabSpec)

        val versionTabSpec = tabHost.newTabSpec("version")
        versionTabSpec.setIndicator(context.getString(R.string.version_and_updates))
        versionTabSpec.setContent(R.id.tabVersion)
        tabHost.addTab(versionTabSpec)

        mainView = findViewById(R.id.tabMain) as MainSettingsView

        setOnDismissListener(this)
    }

    /*internal var onMenuMoreItemClickListener: PopupMenu.OnMenuItemClickListener = PopupMenu.OnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.miUserAgent -> {
                hideMenuOverlay()
                if (viewModel.currentTab.value == null) {
                    return@OnMenuItemClickListener true
                }
                var uaString = viewModel.currentTab.value!!.webView?.settings?.userAgentString
                if (WebViewEx.defaultUAString == uaString) {
                    uaString = ""
                }
                UserAgentConfigDialogFactory.show(this@MainActivity, uaString!!, object : UserAgentConfigDialogFactory.Callback {
                    override fun onDone(defaultUAString: String?) {
                        val editor = prefs!!.edit()
                        editor.putString(USER_AGENT_PREF_KEY, defaultUAString)
                        editor.apply()
                        for (tab in viewModel.tabsStates) {
                            if (tab.webView != null) {
                                tab.webView?.settings?.userAgentString = defaultUAString
                            }
                        }
                        refresh()
                    }
                })

                true
            }
            R.id.miShortcutMenu, R.id.miShortcutNavigateBack, R.id.miShortcutNavigateHome, R.id.miShortcutRefreshPage, R.id.miShortcutVoiceSearch -> {
                ShortcutDialog(this@MainActivity,
                        ShortcutMgr.getInstance(this@MainActivity)
                                .findForMenu(item.itemId)!!
                ).show()
                true
            }
            else -> false
        }
    }*/
}