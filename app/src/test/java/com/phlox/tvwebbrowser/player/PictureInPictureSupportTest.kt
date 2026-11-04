package com.phlox.tvwebbrowser.player

import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.os.Process
import com.phlox.tvwebbrowser.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PictureInPictureSupportTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun setFeature(present: Boolean) =
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, present)

    /** The Google Play build never offers it: TV apps there may not play media in picture-in-picture */
    @Test
    fun theDeviceWithPictureInPictureHasItWhereTheBuildHasIt() {
        setFeature(true)
        assertEquals(BuildConfig.PICTURE_IN_PICTURE, PictureInPictureSupport.isAvailable(context))
    }

    @Test
    fun aDeviceWithoutPictureInPictureHasNoWindow() {
        setFeature(false)
        assertFalse(PictureInPictureSupport.isAvailable(context))
    }

    @Test
    fun nothingOffersItWhenTheUserTurnedItOffForTheApp() {
        setFeature(true)
        val appOps = context.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName,
            AppOpsManager.MODE_IGNORED)
        assertFalse(PictureInPictureSupport.isAvailable(context))
    }

    @Test
    fun theWindowIsWideUntilTheVideoIsKnown() {
        assertEquals(16f / 9f, PictureInPictureSupport.aspectRatio(null).toFloat(), 0.001f)
        assertEquals(16f / 9f, PictureInPictureSupport.aspectRatio(0f).toFloat(), 0.001f)
        assertEquals(16f / 9f, PictureInPictureSupport.aspectRatio(Float.NaN).toFloat(), 0.001f)
    }

    @Test
    fun theWindowHasTheShapeOfTheVideo() {
        assertEquals(4f / 3f, PictureInPictureSupport.aspectRatio(4f / 3f).toFloat(), 0.001f)
        assertEquals(9f / 16f, PictureInPictureSupport.aspectRatio(9f / 16f).toFloat(), 0.001f)
    }

    /** The platform fails a window wider or taller than 2.39:1 */
    @Test
    fun aVeryWideOrTallVideoGetsTheWidestOrTallestWindow() {
        val wide = PictureInPictureSupport.aspectRatio(4f).toFloat()
        val tall = PictureInPictureSupport.aspectRatio(0.1f).toFloat()
        assertEquals(PictureInPictureSupport.MAX_RATIO, wide, 0.001f)
        assertEquals(1f / PictureInPictureSupport.MAX_RATIO, tall, 0.001f)
        assertTrue(wide <= 2.39f && tall >= 1f / 2.39f)
    }
}
