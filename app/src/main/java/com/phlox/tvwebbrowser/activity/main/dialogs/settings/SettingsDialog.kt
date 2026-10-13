package com.phlox.tvwebbrowser.activity.main.dialogs.settings

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.fedir.segmentedbutton.SegmentedButton
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.AutoUpdateModel
import com.phlox.tvwebbrowser.activity.main.SettingsModel
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.widgets.SegmentedButtonTabsAdapter

class SettingsDialog(context: Context, val model: SettingsModel) :
    Dialog(context, R.style.SettingsDialog),
    DialogInterface.OnDismissListener, VersionSettingsView.Callback {
    private var sbTabs: SegmentedButton
    private val btnVersionTab: Button
    private val autoUpdateModel: AutoUpdateModel? =
        (context as? Activity)?.let { ActiveModelsRepository.get(AutoUpdateModel::class, it) }

    /** Invoked after the dialog's own [onDismiss] work, for callers that need to react to changes. */
    var onDismissed: (() -> Unit)? = null

    init {
        setTitle(R.string.settings)
        setContentView(R.layout.dialog_settings)

        sbTabs = findViewById(R.id.sbTabs)
        btnVersionTab = findViewById(R.id.btnVersionTab)

        val tabContentAdapter = object : SegmentedButtonTabsAdapter(sbTabs, findViewById(R.id.flTabsContent)) {
            override fun createContentViewForSegmentButtonId(id: Int): View {
                return when (id) {
                    R.id.btnGeneralTab -> GeneralSettingsView(context)
                    R.id.btnAdsTab -> AdsSettingsView(context)
                    R.id.btnAppearanceTab -> AppearanceSettingsView(context)
                    R.id.btnPlaybackTab -> PlaybackSettingsView(context)
                    R.id.btnControlsTab -> ControlsSettingsView(context)
                    R.id.btnAdvancedTab -> AdvancedSettingsView(context)
                    else -> {
                        val view = VersionSettingsView(context)
                        view.callback = this@SettingsDialog
                        view
                    }
                }
            }
        }

        setOnDismissListener(this)
        refreshAboutTab()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT)
    }

    override fun onDismiss(dialog: DialogInterface?) {
        onDismissed?.invoke()
    }

    override fun onNeedToCloseSettings() {
        dismiss()
    }

    override fun onUpdateStateChanged() {
        refreshAboutTab()
    }

    /**
     * Shows a small accent dot next to the About category while an update is available, mirroring the
     * toolbar settings badge so the remote user can spot it from the category rail.
     */
    private fun refreshAboutTab() {
        val available = autoUpdateModel?.isUpdateAvailable() == true
        btnVersionTab.text = context.getString(R.string.settings_category_about)
        btnVersionTab.setCompoundDrawablesWithIntrinsicBounds(
            0, 0, if (available) R.drawable.ic_update_badge_circle else 0, 0
        )
    }
}
