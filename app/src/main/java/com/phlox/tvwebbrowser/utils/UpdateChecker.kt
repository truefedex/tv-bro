package com.phlox.tvwebbrowser.utils

import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.text.Html
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.phlox.tvwebbrowser.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL


class UpdateChecker(val currentVersionCode: Int) {
    var versionCheckResult: UpdateCheckResult? = null

    class ChangelogEntry(val versionCode: Int, val versionName: String, val changes: String)
    class UpdateCheckResult(val latestVersionCode: Int, val latestVersionName: String, val channel:String,
                            val url: String, val changelog: ArrayList<ChangelogEntry>, val availableChannels: Array<String>)
    interface DialogCallback {
        fun download()
        fun later()
        fun settings()
    }

    fun check(urlOfVersionFile: String, channelsToCheck: Array<String>) {
        val urlConnection = URL(urlOfVersionFile).openConnection() as HttpURLConnection
        try {
            val content = urlConnection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(content)
            val channelsJson = json.getJSONArray("channels")
            var latestVersionCode = 0
            var latestVersionName = ""
            var url = ""
            var latestVersionChannelName = ""
            val availableChannels = ArrayList<String>()
            for (i in 0 until channelsJson.length()) {
                val channelJson = channelsJson.getJSONObject(i)
                availableChannels.add(channelJson.getString("name"))
                if (channelsToCheck.contains(channelJson.getString("name"))) {
                    val minAPI = if (channelJson.has("minAPI")) channelJson.getInt("minAPI") else 21
                    if (latestVersionCode < channelJson.getInt("latestVersionCode") &&
                            minAPI <= Build.VERSION.SDK_INT) {
                        latestVersionCode = channelJson.getInt("latestVersionCode")
                        latestVersionName = channelJson.getString("latestVersionName")
                        url = channelJson.getString("url")
                        if (channelJson.has("zip")) {
                            url = channelJson.getString("zip")
                        }
                        latestVersionChannelName = channelJson.getString("name")
                    }
                }
            }
            val changelogJson = json.getJSONArray("changelog")
            val changelog = ArrayList<ChangelogEntry>()
            for (i in 0 until changelogJson.length()) {
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

    fun showUpdateDialog(context: Context, callback: DialogCallback) {
        val version = versionCheckResult ?: return
        if (version.latestVersionCode < currentVersionCode) {
            throw IllegalStateException("Version less than current")
        }
        var message = ""
        for (changelogEntry in version.changelog) {
            if (changelogEntry.versionCode > currentVersionCode) {
                message += "<b>${changelogEntry.versionName}</b><br>" +
                        changelogEntry.changes.replace("\n", "<br>") + "<br>"
            }
        }
        val textView = TextView(context)
        textView.text = Html.fromHtml(message)
        val padding = Utils.D2P(context, 25f).toInt()
        textView.setPadding(padding, padding, padding, padding)
        AlertDialog.Builder(context)
                .setTitle(R.string.new_version_dialog_title)
                .setView(textView)
                .setPositiveButton(R.string.download) { _, _ -> callback.download() }
                .setNegativeButton(R.string.later) { _, _ -> callback.later() }
                .setNeutralButton(R.string.settings) { _, _ -> callback.settings() }
                .show()
    }

    fun downloadUpdate(context: Context) = GlobalScope.launch(Dispatchers.Main) {
        val update = versionCheckResult ?: return@launch
        val dialog = ProgressDialog(context)
        dialog.setCancelable(true)
        dialog.setMessage(context.getString(R.string.downloading_file))
        dialog.isIndeterminate = false
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.show()
        var downloaded = false
        val zip = update.url.endsWith(".zip")
        var downloadedFile = Utils.createTempFile(context, if (zip) "update.zip" else "update.apk")
        var nextDialogUpdateTime = System.currentTimeMillis()

        val job = launch(Dispatchers.IO) io_launch@{
            var input: InputStream? = null
            var output: OutputStream? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(update.url)
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
                output = downloadedFile!!.outputStream()
                val data = ByteArray(8 * 1024)
                var total: Long = 0
                var count: Int
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
                        withContext(Dispatchers.Main) {
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

            if (zip) {
                var apkFileName: String? = null//we assume there are only one file in zip
                Utils.unzipFile(downloadedFile, downloadedFile.parentFile!!) { progress, filename ->
                    apkFileName = filename
                    if (System.currentTimeMillis() >= nextDialogUpdateTime) {
                        launch(Dispatchers.Main) {
                            dialog.progress = progress
                        }
                        nextDialogUpdateTime += 50
                    }
                }
                downloadedFile.delete()
                downloadedFile = File(downloadedFile.parent!!, apkFileName)
            }

            downloaded = true
        }
        dialog.setOnCancelListener {
            job.cancel()
        }
        job.join()
        dialog.dismiss()

        if (!downloaded) return@launch

        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(downloadedFile.extension)
        val apkURI = FileProvider.getUriForFile(
                context,
                context.applicationContext.packageName + ".provider", downloadedFile)

        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
        install.setDataAndType(apkURI, mimeType)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show()
        }
    }

    fun hasUpdate(): Boolean {
        val versionCheckResult = this.versionCheckResult ?: return false
        return versionCheckResult.latestVersionCode > currentVersionCode
    }
}