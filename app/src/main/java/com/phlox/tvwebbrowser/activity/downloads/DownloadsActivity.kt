package com.phlox.tvwebbrowser.activity.downloads

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.DownloadLauncher
import com.phlox.tvwebbrowser.browser.DownloadsModel
import com.phlox.tvwebbrowser.databinding.ActivityDownloadsBinding
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadFileActions
import com.phlox.tvwebbrowser.utils.SystemBars
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.widgets.datedlist.ListDividerDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderDecoration
import com.phlox.tvwebbrowser.widgets.datedlist.StickyHeaderLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class DownloadsActivity : AppCompatActivity() {
    private lateinit var vb: ActivityDownloadsBinding
    private lateinit var adapter: DownloadListAdapter
    private lateinit var downloadsModel: DownloadsModel
    private val downloadLauncher = DownloadLauncher(this) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate this:" + System.identityHashCode(this))
        vb = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(vb.root)
        SystemBars.matchIconsToTheme(window)

        downloadsModel = ActiveModelsRepository.get(DownloadsModel::class, this)

        adapter = DownloadListAdapter(this)
        val stickyHeader = StickyHeaderDecoration(adapter)
        vb.listView.layoutManager = StickyHeaderLayoutManager(this, stickyHeader)
        vb.listView.itemAnimator = null
        vb.listView.addItemDecoration(ListDividerDecoration(vb.listView, adapter))
        vb.listView.addItemDecoration(stickyHeader)
        vb.listView.adapter = adapter

        lifecycleScope.launch {
            downloadsModel.downloads.collect { items ->
                if (items.isEmpty()) return@collect
                vb.tvPlaceholder.visibility = View.GONE
                val first = adapter.items.isEmpty()
                adapter.submitItems(items.map { it.toDownload() }) {
                    if (first) vb.listView.requestFocus()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        downloadLauncher.bindService()
    }

    override fun onStop() {
        super.onStop()
        downloadLauncher.unbindService()
    }

    override fun onDestroy() {
        ActiveModelsRepository.markAsNeedless(downloadsModel, this)
        super.onDestroy()
    }

    fun onItemClick(v: DownloadListItemView) {
        val download = v.download ?: return

        if (download.status != Download.Status.DONE) {
            //file should be already marked in UI as broken
            return
        }


        val fileURI = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Uri.parse(download.filepath)
        } else {
            val file = File(download.filepath)
            if (!file.exists()) {
                Utils.showToast(this, R.string.file_not_found)
                return
            }

            FileProvider.getUriForFile(this@DownloadsActivity,
                BuildConfig.APPLICATION_ID + ".provider",
                file)
        }


        val openIntent = Intent(Intent.ACTION_VIEW)
        val mimeType = contentResolver.getType(fileURI)
        openIntent.setDataAndType(fileURI, mimeType)
        openIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            startActivity(openIntent)
        } catch (e: ActivityNotFoundException) {
            Utils.showToast(this, getString(R.string.no_app_for_file_type))
        }

    }

    fun onItemLongClick(v: DownloadListItemView): Boolean {
        val download = v.download ?: return false
        if (download.status != Download.Status.IN_PROGRESS) {
            showFinishedDownloadOptionsPopup(v)
        } else {
            showUnfinishedDownloadOptionsPopup(v)
        }
        return true
    }

    private fun showUnfinishedDownloadOptionsPopup(v: DownloadListItemView) {
        val pm = PopupMenu(this, v, Gravity.BOTTOM)
        pm.menu.add(R.string.cancel)
        pm.setOnMenuItemClickListener {
            downloadLauncher.cancel(v.download!!.id)
            true
        }
        pm.show()
    }

    private fun showFinishedDownloadOptionsPopup(v: DownloadListItemView) {
        val download = v.download ?: return
        val pm = PopupMenu(this, v, Gravity.BOTTOM)
        if (download.filename.endsWith(".apk", true) &&
            download.status == Download.Status.DONE) {
            pm.menu.add(0, 0, 0, R.string.install)
        }
        pm.menu.add(0, 1, 1, R.string.open_folder)
        pm.menu.add(0, 2, 2, R.string.delete)
        pm.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> installAPK(download)
                1 -> openFolder(v)
                2 -> deleteItem(v)
            }
            true
        }
        pm.show()
    }

    private fun installAPK(download: Download) {
        val canInstallFromOtherSources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //packageManager.canRequestPackageInstalls()
            true
        } else
            Settings.Secure.getInt(this.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1

        if(canInstallFromOtherSources) {
            launchInstallAPKActivity(this, download)
        } else {
            AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        R.string.turn_on_unknown_sources_for_app else R.string.turn_on_unknown_sources)
                    .setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { dialog, which -> run {
                        val intentSettings = Intent()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            intentSettings.action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                            intentSettings.data = Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                        } else {
                            intentSettings.action = Settings.ACTION_SECURITY_SETTINGS
                        }
                        intentSettings.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(intentSettings)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
                        }
                    }})
                    .show()

        }
    }

    private fun openFolder(v: DownloadListItemView) {
        val uri = Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "resource/folder")
        if (intent.resolveActivityInfo(packageManager, 0) != null) {
            startActivity(intent)
        } else {
            Utils.showToast(this, R.string.no_file_explorer_msg)
        }
    }

    private fun deleteItem(v: DownloadListItemView) = lifecycleScope.launch(Dispatchers.Main) {
        v.download?.let {
            withContext(Dispatchers.IO) { DownloadFileActions.deleteFile(this@DownloadsActivity, it) }
            TVBro.container.data.downloads.delete(it)
        }
    }

    companion object {
        val TAG: String = DownloadsActivity::class.java.simpleName

        fun launchInstallAPKActivity(activity: Activity, download: Download) {
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("apk")
            val apkURI = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Uri.parse(download.filepath)
            } else {
                val file = File(download.filepath)
                FileProvider.getUriForFile(
                    activity,
                    activity.applicationContext.packageName + ".provider", file
                )
            }

            val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
            install.setDataAndType(apkURI, mimeType)
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(install)
            } catch (e: Exception) {
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
