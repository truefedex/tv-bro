package com.phlox.tvwebbrowser.activity.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.utils.UpdateChecker
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import com.phlox.tvwebbrowser.utils.sameDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class AutoUpdateModel: ActiveModel() {
    val config = TVBro.config
    var needToShowUpdateDlgAgain: Boolean = false
    val updateChecker = UpdateChecker(BuildConfig.VERSION_CODE)
    var lastUpdateNotificationTime: Calendar
    var needAutoCheckUpdates: Boolean
        get() = config.autoCheckUpdates && BuildConfig.BUILT_IN_AUTO_UPDATE
        set(value) { config.autoCheckUpdates = value }

    init {
        lastUpdateNotificationTime = if (config.prefs.contains(Config.LAST_UPDATE_USER_NOTIFICATION_TIME_KEY))
            Calendar.getInstance().apply { timeInMillis = config.prefs.getLong(Config.LAST_UPDATE_USER_NOTIFICATION_TIME_KEY, 0) } else
            Calendar.getInstance()
    }

    fun checkUpdate(force: Boolean, onDoneCallback: () -> Unit) = modelScope.launch(Dispatchers.Main) {
        if (updateChecker.versionCheckResult == null || force) {
            launch(Dispatchers.IO) {
                try {
                    updateChecker.check("https://raw.githubusercontent.com/truefedex/tv-bro/master/latest_version.json",
                        arrayOf(config.updateChannel))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.join()
        }
        onDoneCallback()
    }

    fun showUpdateDialogIfNeeded(activity: MainActivity, force: Boolean = false) {
        val now = Calendar.getInstance()
        if (lastUpdateNotificationTime.sameDay(now) && !force) {
            return
        }
        if (!updateChecker.hasUpdate()) {
            throw IllegalStateException()
        }
        lastUpdateNotificationTime = now
        config.prefs.edit()
            .putLong(Config.LAST_UPDATE_USER_NOTIFICATION_TIME_KEY, lastUpdateNotificationTime.timeInMillis)
            .apply()

        updateChecker.showUpdateDialog(activity, object : UpdateChecker.DialogCallback {
            override fun download() {
                if (activity.isFinishing) return
                updateChecker.versionCheckResult ?: return

                val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.packageManager.canRequestPackageInstalls()
                } else
                    Settings.Secure.getInt(activity.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1

                if (canInstallFromOtherSources) {
                    modelScope.launch(Dispatchers.Main) {
                        updateChecker.downloadUpdate(activity, modelScope)
                    }
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.app_name)
                        .setMessage(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            R.string.turn_on_unknown_sources_for_app else R.string.turn_on_unknown_sources)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val intent = Intent()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                intent.action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                                intent.data = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                            } else {
                                intent.action = Settings.ACTION_SECURITY_SETTINGS
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                activity.startActivityForResult(intent, MainActivity.REQUEST_CODE_UNKNOWN_APP_SOURCES)
                                needToShowUpdateDlgAgain = true
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->  }
                        .show()
                }
            }

            override fun later() {}

            override fun settings() {
                if (!activity.isFinishing) {
                    activity.showSettings()
                }
            }
        })
    }

    fun saveAutoCheckUpdates(need: Boolean) {
        config.autoCheckUpdates = need
    }
}