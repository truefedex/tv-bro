package com.phlox.tvwebbrowser.webengine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.io.FileNotFoundException

/**
 * Stands in the manifest for GeckoView's GeckoClipboardContentProvider (same authority), which
 * lives in the gecko dynamic feature module. Content providers are created on app start, so
 * declaring Gecko's provider directly would crash the app while the module is not downloaded.
 * Gecko keeps the clipboard data in static fields, so a separate provider instance can serve it.
 */
class GeckoClipboardProviderProxy : ContentProvider() {
    companion object {
        private const val TAG = "GeckoClipboardProvider"
        private const val GECKO_PROVIDER_CLASS_NAME = "org.mozilla.gecko.GeckoClipboardContentProvider"
    }

    private val delegate: ContentProvider? by lazy {
        try {
            val provider = Class.forName(GECKO_PROVIDER_CLASS_NAME)
                .getDeclaredConstructor().newInstance() as ContentProvider
            provider.attachInfo(context, null)
            provider
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Gecko module is not installed")
            null
        }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = delegate?.getType(uri)

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String) =
        delegate?.getStreamTypes(uri, mimeTypeFilter)

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val delegate = delegate ?: throw FileNotFoundException(uri.toString())
        return delegate.openAssetFile(uri, mode)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        delegate?.query(uri, projection, selection, selectionArgs, sortOrder)

    override fun insert(uri: Uri, values: ContentValues?) = delegate?.insert(uri, values)

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?) =
        delegate?.update(uri, values, selection, selectionArgs) ?: 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        delegate?.delete(uri, selection, selectionArgs) ?: 0

    override fun call(method: String, arg: String?, extras: Bundle?) =
        delegate?.call(method, arg, extras)
}
