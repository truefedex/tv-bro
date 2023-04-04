package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import com.phlox.tvwebbrowser.utils.LogUtils
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoDisplay.ScreenshotBuilder
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


open class GeckoViewEx @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GeckoView(context, attrs) {

    private var geckoDisplay: GeckoDisplay? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        screenshotBuilderAccessHack()
    }

    override fun setSession(session: GeckoSession) {
        super.setSession(session)
        screenshotBuilderAccessHack()
    }

    @Suppress("INACCESSIBLE_TYPE")
    private fun screenshotBuilderAccessHack() {
        val display: Any = mDisplay
        //access mDisplay field by reflection
        val geckoDisplayField = display.javaClass.getDeclaredField("mDisplay")
        geckoDisplayField.isAccessible = true
        geckoDisplay = geckoDisplayField.get(display) as GeckoDisplay?
    }

    fun screenshot(): ScreenshotBuilder? {
        geckoDisplay?.let {
            return it.screenshot()
        }
        return null
    }

    suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        val screenshotBuilder = screenshot() ?: return null
        var thumbnail = bitmap
        if (thumbnail == null) {
            try {
                thumbnail = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                e.printStackTrace()
                LogUtils.recordException(e)
                try {
                    thumbnail = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    e.printStackTrace()
                    LogUtils.recordException(e)
                }
            }
        }
        if (thumbnail == null) {
            return null
        }
        thumbnail = suspendCoroutine {
            val screenshotResult = try {
                screenshotBuilder.bitmap(thumbnail).capture()
            } catch (e: Throwable) {
                e.printStackTrace()
                it.resume(null)
                return@suspendCoroutine
            }
            screenshotResult.then({ bitmap ->
                Log.d(GeckoWebEngine.TAG, "Screenshot captured")
                it.resume(thumbnail)
                GeckoResult.fromValue(bitmap)
            }, { throwable ->
                Log.e(GeckoWebEngine.TAG, "Screenshot failed", throwable)
                it.resume(null)
                GeckoResult.fromValue(null)
            })
        }
        return thumbnail
    }
}