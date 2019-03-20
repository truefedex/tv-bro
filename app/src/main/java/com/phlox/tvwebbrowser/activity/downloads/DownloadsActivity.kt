package com.phlox.tvwebbrowser.activity.downloads

import android.app.AlertDialog
import android.app.ListActivity
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.support.v4.content.FileProvider
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*

import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.utils.Utils

import java.io.File
import java.util.ArrayList

import com.phlox.tvwebbrowser.R.string.url

class DownloadsActivity : ListActivity(), AdapterView.OnItemClickListener, DownloadService.Listener, AdapterView.OnItemLongClickListener {
    private var tvPlaceholder: TextView? = null
    private var adapter: DownloadListAdapter? = null
    private var asql: ASQL? = null
    private var loading = false
    private var downloadsService: DownloadService? = null
    private val listeners = ArrayList<DownloadService.Listener>()

    private val sqlCallback = ASQL.ResultCallback<List<Download>> { result, error ->
        loading = false
        if (result != null) {
            if (!result.isEmpty()) {
                tvPlaceholder!!.visibility = View.GONE
                adapter!!.addItems(result)
                listView.requestFocus()
            }
        } else {
            Utils.showToast(this@DownloadsActivity, R.string.error)
        }
    }

    internal var onListScrollListener: AbsListView.OnScrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {

        }

        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
            if (totalItemCount != 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                loadItems()
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
        setContentView(R.layout.activity_downloads)
        tvPlaceholder = findViewById<View>(R.id.tvPlaceholder) as TextView

        adapter = DownloadListAdapter(this)
        listAdapter = adapter
        asql = ASQL.getDefault(this)

        listView.setOnScrollListener(onListScrollListener)
        listView.onItemClickListener = this
        listView.onItemLongClickListener = this

        loadItems()
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
        val file = File(v.download?.filepath)
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
            launchInstallAPKActivity(download)
        } else {
            AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.turn_on_unknown_sources)
                    .setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { dialog, which -> run {
                        val intentSettings = Intent()
                        intentSettings.action = android.provider.Settings.ACTION_SECURITY_SETTINGS
                        startActivity(intentSettings)
                    }})
                    .show()

        }
    }

    fun launchInstallAPKActivity(download: Download) {
        val file = File(download.filepath)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
        val apkURI = FileProvider.getUriForFile(
                this,
                this.applicationContext.packageName + ".provider", file)

        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
        install.setDataAndType(apkURI, mimeType)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(install, 1001011)//we are not using result for now
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
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

    private fun deleteItem(v: DownloadListItemView) {
        File(v.download?.filepath).delete()
        asql!!.delete(v.download) { result, exception ->
            if (exception != null) {
                Utils.showToast(this@DownloadsActivity, R.string.error)
            } else {
                adapter!!.remove(v.download!!)
            }
        }
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

    private fun loadItems() {
        if (loading) {
            return
        }
        loading = true

        asql!!.queryAll(Download::class.java, "SELECT * FROM downloads ORDER BY time DESC LIMIT 100 OFFSET ?",
                sqlCallback, java.lang.Long.toString(adapter!!.realCount))
    }

    companion object {

        internal fun getFileExtension(filePath: String): String? {
            var result = ""
            val parts = filePath.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size > 0)
                result = parts[parts.size - 1]
            return result
        }
    }
}
