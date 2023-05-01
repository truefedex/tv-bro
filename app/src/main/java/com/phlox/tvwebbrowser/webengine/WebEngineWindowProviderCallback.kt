package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.HomePageLink
import java.io.InputStream

interface WebEngineWindowProviderCallback {
    fun getActivity(): Activity
    fun onOpenInNewTabRequested(url: String, navigateImmediately: Boolean): WebEngine?
    fun onDownloadRequested(url: String)
    fun onDownloadRequested(url: String, referer: String, originalDownloadFileName: String, userAgent: String?, mimeType: String?,
                            operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
                            base64BlobData: String? = null, stream: InputStream? = null, size: Long = 0L)
    fun onDownloadRequested(url: String, userAgent: String?, contentDisposition: String, mimetype: String?, contentLength: Long)
    fun onProgressChanged(newProgress: Int)
    fun onReceivedTitle(title: String)
    fun requestPermissions(array: Array<String>): Int
    fun onShowFileChooser(intent: Intent): Boolean
    fun onReceivedIcon(icon: Bitmap)
    fun shouldOverrideUrlLoading(url: String): Boolean
    fun onPageStarted(url: String?)
    fun onPageFinished(url: String?)
    fun onPageCertificateError(url: String?)
    fun isAd(url: Uri, acceptHeader: String?, baseUri: Uri): Boolean?
    fun isAdBlockingEnabled(): Boolean
    fun isDialogsBlockingEnabled(): Boolean
    fun onBlockedAd(uri: String)
    fun onBlockedDialog(newTab: Boolean)
    fun onCreateWindow(dialog: Boolean, userGesture: Boolean): View?
    fun closeWindow(internalRepresentation: Any)
    fun onScaleChanged(oldScale: Float, newScale: Float)
    fun onCopyTextToClipboardRequested(url: String)
    fun onShareUrlRequested(url: String)
    fun onOpenInExternalAppRequested(url: String)
    fun initiateVoiceSearch()
    fun onEditHomePageBookmarkSelected(index: Int)
    fun getHomePageLinks(): List<HomePageLink>
    fun onPrepareForFullscreen()
    fun onExitFullscreen()
    fun onVisited(url: String)
    fun suggestActionsForLink(href: String, x: Int, y: Int)
}