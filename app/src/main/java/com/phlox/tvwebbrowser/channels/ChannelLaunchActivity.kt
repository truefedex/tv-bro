package com.phlox.tvwebbrowser.channels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadFileActions
import com.phlox.tvwebbrowser.utils.DownloadFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A tile of the downloads channel of the home screen: opens the file as OK on its row does (installs an app),
 * or the downloads screen when the file is gone or no app opens it. Shows nothing itself. Runs in the
 * `:downloads` process (see the manifest), so it does not start the browser. Other apps can start it, so the
 * link carries a token signed with a secret of the app.
 */
class ChannelLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, 0L)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val secret = existingSecret(this)
        if (id <= 0L || token == null || secret == null || token != tokenOf(this, id, secret)) {
            Log.w(TAG, "A link to download $id without a valid token")
            finish()
            return
        }
        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                val download = TVBro.container.data.downloads.get(id)
                download != null && download.status == Download.Status.DONE &&
                    DownloadFiles.exists(this@ChannelLaunchActivity, download.filepath) && open(download)
            }
            if (!opened) startActivity(MainActivity.showDownloadsIntent(this@ChannelLaunchActivity, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    private fun open(download: Download): Boolean =
        if (DownloadFileActions.isApk(download)) DownloadFileActions.install(this, download)
        else DownloadFileActions.open(this, download)

    companion object {
        private const val TAG = "ChannelLaunchActivity"
        private const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val EXTRA_TOKEN = "token"
        private const val SECRET_FILE = "channel_links.key"
        private const val SECRET_SIZE = 32

        fun openDownloadIntent(context: Context, downloadId: Long): Intent =
            Intent(context, ChannelLaunchActivity::class.java)
                .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                .putExtra(EXTRA_TOKEN, tokenOf(context, downloadId))

        /** The signature of a link to the download */
        fun tokenOf(context: Context, downloadId: Long, secret: ByteArray = secret(context)): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return mac.doFinal("${context.packageName}:download:$downloadId".toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * The secret of the links, made on first use, out of backups. A file and not a pref: the processes of
         * the app keep their prefs in memory, and one of them could make a second secret.
         */
        @Synchronized
        private fun secret(context: Context): ByteArray {
            existingSecret(context)?.let { return it }
            val file = File(context.noBackupFilesDir, SECRET_FILE)
            val temp = File(context.noBackupFilesDir, "$SECRET_FILE.${android.os.Process.myPid()}.tmp")
            temp.writeBytes(ByteArray(SECRET_SIZE).also { SecureRandom().nextBytes(it) })
            //another process may have made it meanwhile: the first one stays
            if (file.length() != SECRET_SIZE.toLong()) temp.renameTo(file) else temp.delete()
            return file.readBytes()
        }

        /** The secret, null before the first link was made (no link can be valid then) */
        private fun existingSecret(context: Context): ByteArray? =
            File(context.noBackupFilesDir, SECRET_FILE).takeIf { it.length() == SECRET_SIZE.toLong() }?.readBytes()
    }
}
