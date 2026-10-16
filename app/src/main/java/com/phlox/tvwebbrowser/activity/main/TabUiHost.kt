package com.phlox.tvwebbrowser.activity.main

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoriteEditorDialog
import com.phlox.tvwebbrowser.browser.BrowserController
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.utils.SystemBars
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.launchForResult
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import kotlinx.coroutines.launch

/**
 * What the engine of the [tab] needs from the activity that shows it. The parts that depend on
 * how the screen looks (its menus, voice search, what happens in fullscreen) go to [screen].
 */
class TabUiHost(
    private val activity: AppCompatActivity,
    private val tab: BrowserTab,
    private val controller: BrowserController,
    private val downloadLauncher: DownloadLauncher,
    private val screen: Screen
) : EngineUiHost {
    companion object {
        private val TAG = TabUiHost::class.java.simpleName

        /** Opens an "intent:" url in the app it names */
        fun openInExternalApp(activity: Activity, url: String) {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            val activityComponent = intent.resolveActivity(activity.packageManager)
            if (activityComponent != null && activityComponent.packageName == activity.packageName) {
                Toast.makeText(activity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
                return
            }
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(activity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * The parts of the engine needs that differ between the screens.
     */
    interface Screen {
        /** Holds the engine views; menus of links are anchored in it */
        val tabsContainer: ViewGroup
        fun startVoiceSearch()
        fun openInNewTabAfterCurrent(url: String)
        fun showContextMenu(
            tab: BrowserTab, uiHost: EngineUiHost, cursorDrawer: CursorDrawerDelegate,
            baseUri: String?, linkUri: String?, srcUri: String?,
            title: String?, altText: String?, textContent: String?, x: Int, y: Int
        )
        /** The engine shows or hides its fullscreen view (the system bars are already handled) */
        fun onFullscreenChanged(fullscreen: Boolean)
        fun onPageScrolled(scrollY: Int, dy: Int)
    }

    private val config = AppContext.provideConfig()

    override fun getActivity(): Activity = activity

    override fun onDownloadRequested(request: DownloadRequest) {
        Log.i(TAG, "onDownloadRequested url: ${request.url}")
        downloadLauncher.download(request)
    }

    override fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
        activity.launchForResult(ActivityResultContracts.RequestMultiplePermissions(), permissions, onResult)
    }

    override fun pickFile(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit): Boolean {
        val contract = ActivityResultContracts.StartActivityForResult()
        val callback = { result: ActivityResult -> onResult(result.resultCode, result.data) }
        try {
            activity.launchForResult(contract, intent, callback)
        } catch (e: ActivityNotFoundException) {
            try {
                //trying again with type */* (seems file pickers usually doesn't support specific types in intent filters but still can do the job)
                intent.type = "*/*"
                activity.launchForResult(contract, intent, callback)
            } catch (e: ActivityNotFoundException) {
                Utils.showToast(activity.applicationContext, activity.getString(R.string.err_cant_open_file_chooser))
                return false
            }
        }
        return true
    }

    override fun onCopyTextToClipboardRequested(url: String) {
        val clipBoard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("URL", url)
        clipBoard.setPrimaryClip(clipData)
        Toast.makeText(activity, activity.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    override fun onShareUrlRequested(url: String) {
        val share = Intent(Intent.ACTION_SEND)
        share.type = "text/plain"
        share.putExtra(Intent.EXTRA_SUBJECT, R.string.share_url)
        share.putExtra(Intent.EXTRA_TEXT, url)
        try {
            activity.startActivity(share)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(activity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOpenInExternalAppRequested(url: String) = openInExternalApp(activity, url)

    override fun initiateVoiceSearch() {
        screen.startVoiceSearch()
    }

    override fun onEditHomePageBookmarkSelected(index: Int) {
        activity.lifecycleScope.launch {
            val bookmark = controller.homePageLinks.links.value.firstOrNull { it.order == index }
            var favoriteItem: FavoriteItem? = bookmark?.favoriteId?.let {
                TVBro.container.data.favorites.get(it)
            }

            if (favoriteItem == null) {
                favoriteItem = FavoriteItem()
                favoriteItem.title = bookmark?.title
                favoriteItem.url = bookmark?.url
                favoriteItem.order = index
                favoriteItem.homePageBookmark = true
                editHomePageBookmark(favoriteItem)
            } else {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.bookmarks)
                    .setItems(arrayOf(activity.getString(R.string.edit), activity.getString(R.string.delete))) { _, which ->
                        when (which) {
                            0 -> editHomePageBookmark(favoriteItem)
                            1 -> controller.modelScope.launch { controller.homePageLinks.remove(bookmark!!) }
                        }
                    }
                    .show()
            }
        }
    }

    private fun editHomePageBookmark(favoriteItem: FavoriteItem) {
        FavoriteEditorDialog(activity, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                controller.modelScope.launch { controller.homePageLinks.onBookmarkEdited(item) }
            }
        }, favoriteItem).show()
    }

    override fun onPrepareForFullscreen() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SystemBars.hide(activity.window)
        screen.onFullscreenChanged(true)
    }

    override fun onPageScrolled(scrollY: Int, dy: Int) = screen.onPageScrolled(scrollY, dy)

    override fun onExitFullscreen() {
        if (!config.keepScreenOn) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        SystemBars.show(activity.window)
        screen.onFullscreenChanged(false)
    }

    override fun onContextMenu(
        cursorDrawer: CursorDrawerDelegate,
        baseUri: String?,
        linkUri: String?,
        srcUri: String?,
        title: String?,
        altText: String?,
        textContent: String?,
        x: Int,
        y: Int
    ) {
        //the engine may call it while handling an input event
        screen.tabsContainer.post {
            screen.showContextMenu(tab, this, cursorDrawer, baseUri, linkUri, srcUri, title, altText, textContent, x, y)
        }
    }

    override fun suggestActionsForLink(baseUri: String?, linkUri: String?, srcUri: String?,
                                       title: String?, altText: String?, textContent: String?,
                                       x: Int, y: Int) {
        var s = linkUri ?: srcUri
        if (s != null && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        val url = s
        val isHTTPUrl = url != null && (url.startsWith("http://") || url.startsWith("https://"))
        val container = screen.tabsContainer
        val anchor = View(activity)
        val lp = FrameLayout.LayoutParams(1, 1)
        lp.setMargins(x, y, 0, 0)
        container.addView(anchor, lp)
        PopupMenu(activity, anchor, Gravity.BOTTOM).also {
            it.inflate(R.menu.menu_link)
            it.menu.findItem(R.id.miOpenInNewTab).isVisible = isHTTPUrl
            it.menu.findItem(R.id.miOpenInExternalApp).isVisible = isHTTPUrl
            it.menu.findItem(R.id.miDownload).isVisible = isHTTPUrl
            it.menu.findItem(R.id.miCopyToClipboard).isVisible = url != null
            it.menu.findItem(R.id.miShare).isVisible = url != null
            it.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.miRefreshPage -> tab.webEngine.reload()
                    R.id.miOpenInNewTab -> screen.openInNewTabAfterCurrent(url!!)
                    R.id.miOpenInExternalApp -> onOpenInExternalAppRequested(url!!)
                    R.id.miDownload -> onDownloadRequested(
                        DownloadRequest.forLink(url!!, tab.url, tab.webEngine.userAgentString, tab.webEngine.getCookies(url)))
                    R.id.miCopyToClipboard -> onCopyTextToClipboardRequested(url!!)
                    R.id.miShare -> onShareUrlRequested(url!!)
                }
                true
            }

            it.setOnDismissListener {
                container.removeView(anchor)
            }
            it.show()
        }
    }
}
