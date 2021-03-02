package com.phlox.tvwebbrowser.activity.downloads

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ActivityDownloadsBinding
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

class DownloadsActivity : AppCompatActivity(), AdapterView.OnItemClickListener, DownloadService.Listener, AdapterView.OnItemLongClickListener {
    private lateinit var vb: ActivityDownloadsBinding
    private var adapter: DownloadListAdapter? = null
    private var downloadsService: DownloadService? = null
    private val listeners = ArrayList<DownloadService.Listener>()

    private lateinit var viewModel: DownloadsViewModel

    internal var onListScrollListener: AbsListView.OnScrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {

        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
            if (totalItemCount != 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                viewModel.loadItems(adapter!!.realCount)
            }
        }
    }

    internal var downloadsServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DownloadService.Binder
            downloadsService = binder.service
            downloadsService!!.registerListener(this@DownloadsActivity)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            downloadsService!!.unregisterListener(this@DownloadsActivity)
            downloadsService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(vb.root)

        viewModel = ViewModelProvider(this).get(DownloadsViewModel::class.java)

        adapter = DownloadListAdapter(this)
        vb.listView.adapter = adapter

        vb.listView.setOnScrollListener(onListScrollListener)
        vb.listView.onItemClickListener = this
        vb.listView.onItemLongClickListener = this

        viewModel.items.observe(this, {
            if (it.isNotEmpty()) {
                vb.tvPlaceholder.visibility = View.GONE
                adapter!!.addItems(it)
                vb.listView.requestFocus()
            }
        })
        viewModel.loadItems()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DownloadService::class.java), downloadsServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(downloadsServiceConnection)
        super.onStop()
    }

    override fun onItemClick(adapterView: AdapterView<*>, view: View, i: Int, l: Long) {
        val v = view as DownloadListItemView
        if (v.download == null) return
        if (v.download?.isDateHeader!!) {
            return
        }
        val file = File(v.download?.filepath!!)
        if (!file.exists()) {
            Utils.showToast(this, R.string.file_not_found)
        }
        if (v.download?.size != v.download?.bytesReceived) {
            return
        }
        //Uri pathUri = Uri.fromFile(file);
        val pathUri = FileProvider.getUriForFile(this@DownloadsActivity,
                BuildConfig.APPLICATION_ID + ".provider",
                file)
        val openIntent = Intent(Intent.ACTION_VIEW)
        val extension = getFileExtension(v.download?.filepath!!)
        if (extension != null) {
            openIntent.setDataAndType(pathUri, MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension))
        } else {
            openIntent.data = pathUri
        }
        openIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            startActivity(openIntent)
        } catch (e: ActivityNotFoundException) {
            Utils.showToast(this, getString(R.string.no_app_for_file_type))
        }

    }

    override fun onItemLongClick(adapterView: AdapterView<*>, view: View, i: Int, l: Long): Boolean {
        val v = view as DownloadListItemView
        if (v.download == null) return false
        if (v.download?.isDateHeader!!) {
            return true
        }
        if (v.download?.size == Download.BROKEN_MARK || v.download?.size == Download.CANCELLED_MARK ||
                v.download?.size == v.download?.bytesReceived) {
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
            downloadsService!!.cancelDownload(v.download!!)
            true
        }
        pm.show()
    }

    private fun showFinishedDownloadOptionsPopup(v: DownloadListItemView) {
        val pm = PopupMenu(this, v, Gravity.BOTTOM)
        if (v.download?.filename!!.endsWith(".apk", true) &&
                v.download?.size == v.download?.bytesReceived) {
            pm.menu.add(0, 0, 0, R.string.install)
        }
        pm.menu.add(0, 1, 1, R.string.open_folder)
        pm.menu.add(0, 2, 2, R.string.delete)
        pm.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> installAPK(v.download!!)
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
                            startActivityForResult(intentSettings, REQUEST_CODE_UNKNOWN_APP_SOURCES)
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
        File(v.download?.filepath!!).delete()
        AppDatabase.db.downloadDao().delete(v.download!!)
        adapter!!.remove(v.download!!)
    }

    override fun onDownloadUpdated(downloadInfo: Download) {
        for (i in listeners.indices) {
            listeners[i].onDownloadUpdated(downloadInfo)
        }
    }

    override fun onDownloadError(downloadInfo: Download, responseCode: Int, responseMessage: String) {
        for (i in listeners.indices) {
            listeners[i].onDownloadError(downloadInfo, responseCode, responseMessage)
        }
    }

    override fun onAllDownloadsComplete() {}

    fun registerListener(listener: DownloadService.Listener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: DownloadService.Listener) {
        listeners.remove(listener)
    }

    companion object {
        const val REQUEST_CODE_UNKNOWN_APP_SOURCES = 10007
        const val REQUEST_CODE_INSTALL_PACKAGE = 10008

        internal fun getFileExtension(filePath: String): String? {
            var result = ""
            val parts = filePath.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size > 0)
                result = parts[parts.size - 1]
            return result
        }

        fun launchInstallAPKActivity(activity: Activity, download: Download) {
            val file = File(download.filepath)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
            val apkURI = FileProvider.getUriForFile(
                    activity,
                    activity.applicationContext.packageName + ".provider", file)

            val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
            install.setDataAndType(apkURI, mimeType)
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivityForResult(install, REQUEST_CODE_INSTALL_PACKAGE)//we are not using result for now
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
