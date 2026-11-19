package com.phlox.tvwebbrowser.webengine.webview

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.phlox.tvwebbrowser.widgets.cursor.CursorLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * A thumbnail of a page from what the window shows ([PixelCopy], Android 8+), without the cursor. WebView draws
 * a page in software (`draw` on a bitmap canvas) wrongly in some states, the screen shows it right.
 * It waits for the page to draw what it has (a few frames, the bar opens that much later).
 */
object WebViewScreenCapture {
    private const val TAG = "WebViewScreenCapture"

    /**
     * Frames drawn before the copy, after the page has drawn what it has (scripts that just ran) and the cursor
     * was hidden: the frame with them is then on the screen
     */
    private const val FRAMES_BEFORE_COPY = 3

    /** How long the copy waits for the page to draw what it has */
    private const val VISUAL_STATE_TIMEOUT_MS = 500L

    /** The thumbnail ([bitmap] if it fits), or null where the screen can not be copied */
    suspend fun capture(view: WebViewEx, cursorLayout: CursorLayout?, window: Window, bitmap: Bitmap?): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (!view.isAttachedToWindow || !view.isShown || view.width == 0 || view.height == 0) return null
        val hideCursor = cursorLayout?.drawsCursor == true
        if (hideCursor) cursorLayout.cursorHidden = true
        try {
            withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) { awaitVisualState(view) }
            repeat(FRAMES_BEFORE_COPY) { awaitFrame() }
            if (!view.isAttachedToWindow || !view.isShown) return null
            return copy(view, window, bitmap)
        } finally {
            if (hideCursor) cursorLayout.cursorHidden = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun copy(view: View, window: Window, bitmap: Bitmap?): Bitmap? {
        val target = bitmap?.takeIf { it.isMutable && it.config != Bitmap.Config.HARDWARE }
            ?: try {
                createBitmap(view.width, view.height, Bitmap.Config.RGB_565)
            } catch (e: Throwable) {
                Log.e(TAG, "no bitmap for the thumbnail", e)
                return null
            }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val result = try {
            suspendCancellableCoroutine<Int> { continuation ->
                PixelCopy.request(window, rect, target, { result ->
                    if (continuation.isActive) continuation.resume(result)
                }, Handler(Looper.getMainLooper()))
            }
        } catch (e: IllegalArgumentException) {
            //the window has no surface
            Log.w(TAG, "can not copy the window", e)
            return null
        }
        if (result != PixelCopy.SUCCESS) {
            Log.w(TAG, "copy of the window failed: $result")
            return null
        }
        return target
    }

    /** The page has drawn the state its document has now */
    private suspend fun awaitVisualState(view: WebView) = suspendCancellableCoroutine { continuation ->
        view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        })
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { continuation ->
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        Choreographer.getInstance().postFrameCallback(callback)
        continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
    }
}
