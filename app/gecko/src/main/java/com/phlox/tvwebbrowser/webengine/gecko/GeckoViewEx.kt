package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.util.Log
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoDisplay.ScreenshotBuilder
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume


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
                try {
                    thumbnail = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    e.printStackTrace()
                }
            }
        }
        if (thumbnail == null) {
            return null
        }
        //Gecko never answers while it has no compositor (its GPU process was killed for memory and the page is not
        //drawn again): the bar of the TV shell, which opens once the picture is taken, stayed hidden
        val target: Bitmap = thumbnail
        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) { capture(screenshotBuilder, target) }
    }

    private suspend fun capture(screenshotBuilder: ScreenshotBuilder, thumbnail: Bitmap): Bitmap? =
        suspendCancellableCoroutine {
            val screenshotResult = try {
                screenshotBuilder.bitmap(thumbnail).capture()
            } catch (e: Throwable) {
                e.printStackTrace()
                it.resume(null)
                return@suspendCancellableCoroutine
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

    companion object {
        /** How long a thumbnail waits for Gecko to draw the page into it */
        private const val SCREENSHOT_TIMEOUT_MS = 1000L
    }
}