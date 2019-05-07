package com.phlox.tvwebbrowser.utils

import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.support.v4.content.FileProvider
import android.support.v7.app.AlertDialog
import android.text.Html
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import com.phlox.tvwebbrowser.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.lang.IllegalStateException
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.collections.ArrayList


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

    fun downloadUpdate(context: Context) = GlobalScope.launch(Dispatchers.Main) {
        val dialog = ProgressDialog(context)
        dialog.setCancelable(true)
        dialog.setMessage(context.getString(R.string.downloading_file))
        dialog.isIndeterminate = false
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.show()
        var downloaded = false
        var downloadedFile: File? = null

        val job = launch(Dispatchers.IO) io_launch@{
            var input: InputStream? = null
            var output: OutputStream? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(versionCheckResult!!.url)
                connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, (connection.responseMessage ?: context.getString(R.string.error)) +
                                " (${connection.responseCode})", Toast.LENGTH_LONG).show()
                    }
                    return@io_launch
                }

                val fileLength = connection.contentLength
                if (fileLength == -1) {
                    launch(Dispatchers.Main) {
                        dialog.isIndeterminate = true
                        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
                    }
                }

                input = connection.inputStream
                downloadedFile = Utils.createTempFile(context, "update.apk")
                output = downloadedFile!!.outputStream()
                val data = ByteArray(8 * 1024)
                var total: Long = 0
                var count: Int
                var nextDialogUpdateTime = System.currentTimeMillis()
                do {
                    if (!isActive) {
                        return@io_launch
                    }
                    count = input.read(data)
                    if (count > 0) {
                        total += count.toLong()
                        output.write(data, 0, count)
                    }
                    if (fileLength != -1 && System.currentTimeMillis() >= nextDialogUpdateTime) {
                        launch(Dispatchers.Main) {
                            val progress = total * 100 / fileLength
                            dialog.progress = progress.toInt()
                        }
                        nextDialogUpdateTime += 50
                    }
                } while (count != -1)
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, e.toString(), Toast.LENGTH_LONG).show()
                }
                return@io_launch
            } finally {
                output?.close()
                input?.close()
                connection?.disconnect()
            }
            downloaded = true
        }
        dialog.setOnCancelListener {
            job.cancel()
        }
        job.join()
        dialog.dismiss()

        if (!downloaded || downloadedFile == null) return@launch

        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(downloadedFile!!.extension)
        val apkURI = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider", downloadedFile!!)

        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
        install.setDataAndType(apkURI, mimeType)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
        }
    }

    fun hasUpdate(): Boolean {
        return versionCheckResult != null && versionCheckResult!!.latestVersionCode > currentVersionCode
    }
}