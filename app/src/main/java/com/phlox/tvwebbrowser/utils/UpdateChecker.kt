package com.phlox.tvwebbrowser.utils

import android.app.Activity
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.Html
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.phlox.tvwebbrowser.R
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(val currentVersionName: String) {
    var versionCheckResult: UpdateCheckResult? = null

    class ChangelogEntry(val versionName: String, val changes: String)
    class UpdateCheckResult(val latestVersionName: String, val channel:String,
                            val url: String, val changelog: ArrayList<ChangelogEntry>, val availableChannels: Array<String>)
    interface DialogCallback {
        fun download()
        fun later()
        fun settings()
    }

    /**
     * Looks for a newer build among the GitHub Releases of [repo] ("owner/name") instead of a custom
     * version manifest, so that a fork can ship its own releases independently from upstream.
     *
     * [channelsToCheck] controls pre-releases: the [CHANNEL_RELEASE] channel ignores them, while any
     * other channel (e.g. [CHANNEL_PRERELEASE]) includes them. Only releases that expose an APK asset
     * for the running build ([appStoreFlavor] + [webEngineFlavor], plus [abi] when the engine ships
     * native code) are considered, mirroring the asset naming of `.github/workflows/release.yml`.
     */
    fun checkGitHubReleases(repo: String, channelsToCheck: Array<String>, appStoreFlavor: String,
                            webEngineFlavor: String, abi: String) {
        val includePrereleases = channelsToCheck.any { it != CHANNEL_RELEASE }
        val urlConnection = URL("https://api.github.com/repos/$repo/releases?per_page=30").openConnection() as HttpURLConnection
        try {
            //GitHub's API rejects requests without a User-Agent
            urlConnection.setRequestProperty("User-Agent", USER_AGENT)
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json")
            val content = urlConnection.inputStream.bufferedReader().use { it.readText() }
            val releasesJson = JSONArray(content)
            val assetNeedle = "-$appStoreFlavor-$webEngineFlavor"

            var latestVersionName: String? = null
            var latestUrl = ""
            var latestChannel = CHANNEL_RELEASE
            var latestBody = ""
            val newerReleases = ArrayList<Pair<String, String>>()

            for (i in 0 until releasesJson.length()) {
                val releaseJson = releasesJson.getJSONObject(i)
                if (releaseJson.optBoolean("draft")) continue
                val isPrerelease = releaseJson.optBoolean("prerelease")
                if (isPrerelease && !includePrereleases) continue
                val tag = releaseJson.optString("tag_name")
                if (tag.isEmpty()) continue

                val assetUrl = findAssetUrl(releaseJson.optJSONArray("assets"), assetNeedle, abi) ?: continue

                if (latestVersionName == null || compareVersions(tag, latestVersionName) > 0) {
                    latestVersionName = tag
                    latestUrl = assetUrl
                    latestChannel = if (isPrerelease) CHANNEL_PRERELEASE else CHANNEL_RELEASE
                    latestBody = releaseJson.optString("body")
                }
                if (compareVersions(tag, currentVersionName) > 0) {
                    newerReleases.add(tag to releaseJson.optString("body"))
                }
            }

            if (latestVersionName == null) {
                //a successful check with nothing to offer: cache an empty result so we don't re-fetch
                versionCheckResult = UpdateCheckResult("", "", "", ArrayList<ChangelogEntry>(), AVAILABLE_CHANNELS)
                return
            }

            newerReleases.sortWith { a, b -> compareVersions(b.first, a.first) }
            val changelog = ArrayList<ChangelogEntry>()
            for ((tag, body) in newerReleases) {
                changelog.add(ChangelogEntry(tag, body))
            }
            if (changelog.isEmpty()) {
                changelog.add(ChangelogEntry(latestVersionName, latestBody))
            }

            versionCheckResult = UpdateCheckResult(latestVersionName, latestChannel, latestUrl, changelog, AVAILABLE_CHANNELS)
        } finally {
            urlConnection.disconnect()
        }
    }

    private fun findAssetUrl(assetsJson: JSONArray?, assetNeedle: String, abi: String): String? {
        if (assetsJson == null) return null
        var fallback: String? = null
        for (j in 0 until assetsJson.length()) {
            val asset = assetsJson.getJSONObject(j)
            val name = asset.optString("name")
            if (!name.contains(assetNeedle)) continue
            val assetUrl = asset.optString("browser_download_url")
            if (name.endsWith("-$abi.apk")) return assetUrl
            if (fallback == null) fallback = assetUrl
        }
        return fallback
    }

    fun showUpdateDialog(context: Activity, callback: DialogCallback) {
        val version = versionCheckResult ?: return
        var message = ""
        for (changelogEntry in version.changelog) {
            if (compareVersions(changelogEntry.versionName, currentVersionName) > 0) {
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

    suspend fun downloadUpdate(context: Activity, modelScope: CoroutineScope) {
        val update = versionCheckResult ?: return
        val dialog = ProgressDialog(context)
        dialog.setCancelable(true)
        dialog.setMessage(context.getString(R.string.downloading_file))
        dialog.isIndeterminate = false
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.show()
        var downloaded = false
        var downloadedFile = Utils.createTempFile(context, UPDATE_APK_FILE_NAME)
        var nextDialogUpdateTime = System.currentTimeMillis()

        val job = modelScope.launch(Dispatchers.IO) io_launch@{
            var input: InputStream? = null
            var output: OutputStream? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(update.url)
                connection = url.openConnection() as HttpURLConnection
                // GitHub release assets answer with a 302 to a signed object URL; HttpURLConnection
                // follows GET redirects, but only within a protocol, and GitHub wants a User-Agent.
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, (connection.responseMessage ?: context.getString(R.string.error)) +
                                " (${connection.responseCode})", Toast.LENGTH_LONG).show()
                    }
                    return@io_launch
                }

                var fileLength = connection.contentLength.toLong()
                if (fileLength == -1L) {
                    withContext(Dispatchers.Main) {
                        dialog.isIndeterminate = true
                        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
                    }
                }

                input = connection.inputStream
                output = downloadedFile.outputStream()
                val data = ByteArray(8 * 1024)
                var total: Long = 0
                var count: Int
                do {
                    if (!isActive) {
                        return@io_launch
                    }
                    count = input!!.read(data)
                    if (count > 0) {
                        total += count.toLong()
                        output.write(data, 0, count)
                    }
                    if (fileLength != -1L && System.currentTimeMillis() >= nextDialogUpdateTime) {
                        withContext(Dispatchers.Main) {
                            val progress = total * 100 / fileLength
                            dialog.progress = progress.toInt()
                        }
                        nextDialogUpdateTime += 50
                    }
                } while (count != -1)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
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

        if (!downloaded) return

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
        return compareVersions(versionCheckResult.latestVersionName, currentVersionName) > 0
    }

    companion object {
        const val CHANNEL_RELEASE = "release"
        const val CHANNEL_PRERELEASE = "pre-release"
        val AVAILABLE_CHANNELS = arrayOf(CHANNEL_RELEASE, CHANNEL_PRERELEASE)
        private const val USER_AGENT = "tv-bro-updater"
        private const val UPDATE_APK_FILE_NAME = "update.apk"

        /**
         * Compares two dotted version strings (a leading "v" and any suffix are ignored), returning a
         * negative value if [a] is older than [b], zero if they are equal, and a positive value if
         * [a] is newer. Comparison is numeric per component, so "2.1.10" is newer than "2.1.9".
         */
        fun compareVersions(a: String, b: String): Int {
            val partsA = versionParts(a)
            val partsB = versionParts(b)
            for (i in 0 until maxOf(partsA.size, partsB.size)) {
                val diff = partsA.getOrElse(i) { 0 } - partsB.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }

        private fun versionParts(version: String): List<Int> =
            version.trim().removePrefix("v").split('.', '-', '+')
                .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

        fun clearTempFilesIfAny(context: Context) {
            var tempFile = File(context.cacheDir, UPDATE_APK_FILE_NAME)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            tempFile = File(context.externalCacheDir, UPDATE_APK_FILE_NAME)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
