package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.fedir.segmentedbutton.SegmentedButton
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.widgets.SegmentedButtonTabsAdapter

/**
 * @param touchUi the settings of the phone UI: without the shortcuts (keys of remotes) and the cursor settings
 */
class SettingsDialog(context: Context, val model: SettingsModel, private val touchUi: Boolean) :
    Dialog(context, R.style.SettingsDialog),
    DialogInterface.OnDismissListener, VersionSettingsView.Callback {
    private var mainView: MainSettingsView? = null
    private var sbTabs: SegmentedButton

    init {
        setTitle(R.string.settings)
        setContentView(R.layout.dialog_settings)

        sbTabs = findViewById(R.id.sbTabs)
        if (touchUi) {
            findViewById<View>(R.id.btnShortcutsTab).visibility = View.GONE
        }

        val tabContentAdapter = object : SegmentedButtonTabsAdapter(sbTabs, findViewById(R.id.flTabsContent)) {
            override fun createContentViewForSegmentButtonId(id: Int): View {
                return when (id) {
                    R.id.btnMainTab -> {
                        mainView = MainSettingsView(context).apply {
                            if (touchUi) hideCursorSettings()
                        }
                        mainView!!
                    }
                    R.id.btnShortcutsTab -> ShortcutsSettingsView(context)
                    else -> {
                        val view = VersionSettingsView(context)
                        view.callback = this@SettingsDialog
                        view
                    }
                }
            }
        }

        setOnDismissListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT)
    }

    override fun onDismiss(dialog: DialogInterface?) {
        mainView?.save()
    }

    override fun onNeedToCloseSettings() {
        dismiss()
    }
}