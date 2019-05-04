package com.phlox.tvwebbrowser.utils

import android.content.Context
import android.content.DialogInterface
import android.support.v7.app.AlertDialog
import android.text.Html
import android.widget.TextView
import com.phlox.tvwebbrowser.R
import org.json.JSONObject
import java.lang.IllegalStateException
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap


class UpdateChecker(val currentVersionCode: Int) {
    var versionCheckResult: UpdateCheckResult? = null

    companion object {
        fun isInstalledByMarket(context: Context): Boolean {
            // A list with valid installers package name
            val validInstallers = ArrayList(Arrays.asList("com.android.vending", "com.google.android.feedback"))
            val installer = context.packageManager.getInstallerPackageName(context.packageName)
            return installer != null && validInstallers.contains(installer)
        }
    }

    class ChangelogEntry(val versionCode: Int, val versionName: String, val changes: String)
    class UpdateCheckResult(val latestVersionCode: Int, val latestVersionName: String, val channel:String,
                            val url: String, val changelog: ArrayList<ChangelogEntry>, val availableChannels: Array<String>)
    interface DialogCallback {
        fun download()
        fun later()
        fun settings()
    }

    fun check(urlOfVersionFile: String, channelsToCheck: Array<String>) {
        val url = URL(urlOfVersionFile)
        val urlConnection = url.openConnection() as HttpURLConnection
        try {
            val content = urlConnection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(content)
            val channelsJson = json.getJSONArray("channels")
            var latestVersionCode = 0
            var latestVersionName = ""
            var url = ""
            var latestVersionChannelName = ""
            var availableChannels = ArrayList<String>()
            for (i in 0 until channelsJson.length()) {
                val channelJson = channelsJson.getJSONObject(i)
                availableChannels.add(channelJson.getString("name"))
                if (channelsToCheck.contains(channelJson.getString("name"))) {
                    if (latestVersionCode < channelJson.getInt("latestVersionCode")) {
                        latestVersionCode = channelJson.getInt("latestVersionCode")
                        latestVersionName = channelJson.getString("latestVersionName")
                        url = channelJson.getString("url")
                        latestVersionChannelName = channelJson.getString("name")
                    }
                }
            }
            val changelogJson = json.getJSONArray("changelog")
            val changelog = ArrayList<ChangelogEntry>()
            for (i in 0..(changelogJson.length() - 1)) {
                val versionChangesJson = changelogJson.getJSONObject(i)
                changelog.add(ChangelogEntry(versionChangesJson.getInt("versionCode"),
                        versionChangesJson.getString("versionName"),
                        versionChangesJson.getString("changes")))
            }
            versionCheckResult = UpdateCheckResult(latestVersionCode, latestVersionName, latestVersionChannelName, url, changelog, availableChannels.toTypedArray())
        } finally {
            urlConnection.disconnect()
        }
    }

    fun showUpdateDialog(context: Context, channel: String, callback: DialogCallback) {
        if (versionCheckResult == null || versionCheckResult!!.latestVersionCode <= currentVersionCode) {
            throw IllegalStateException("Latest version not defined or less than current")
        }
        var message = ""
        for (changelogEntry in versionCheckResult!!.changelog) {
            if (changelogEntry.versionCode > currentVersionCode) {
                message += "<b>${changelogEntry.versionName}</b><br>" +
                        changelogEntry.changes.replace("\n", "<br>")
            }
        }
        val textView = TextView(context)
        textView.text = Html.fromHtml(message)
        val padding = Utils.D2P(context, 25f).toInt()
        textView.setPadding(padding, padding, padding, padding)
        AlertDialog.Builder(context)
                .setTitle(R.string.new_version_dialog_title)
                .setView(textView)
                .setPositiveButton(R.string.download, DialogInterface.OnClickListener { dialog, which ->
                    callback.download()
                })
                .setNegativeButton(R.string.later, DialogInterface.OnClickListener { dialog, which ->
                    callback.later()
                })
                .setNeutralButton(R.string.settings, DialogInterface.OnClickListener { dialog, which ->
                    callback.settings()
                })
                .show()
    }

    fun hasUpdate(): Boolean {
        return versionCheckResult != null && versionCheckResult!!.latestVersionCode > currentVersionCode
    }
}