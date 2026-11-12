package com.phlox.tvwebbrowser

import android.content.ComponentName
import com.phlox.tvwebbrowser.AppProcesses.Kind
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.KidsProfileMainActivity
import com.phlox.tvwebbrowser.activity.main.MainActivity
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppProcessesTest {
    private val pkg = "com.phlox.tvwebbrowser.gecko"

    @Test
    fun theBrowserProcessesAreInitialized() {
        assertEquals(Kind.BROWSER, AppProcesses.kindOf(pkg, pkg))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("$pkg:incognito", pkg))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("$pkg:kids", pkg))
    }

    /** Only its process has the database and the pages data of the kids profile */
    @Test
    fun onlyTheKidsProcessIsOfTheKidsProfile() {
        assertTrue(AppProcesses.isKids("$pkg:kids", pkg))
        for (name in listOf(pkg, "$pkg:incognito", "$pkg:downloads", "$pkg:kids_1", "", "other:kids")) {
            assertFalse(name, AppProcesses.isKids(name, pkg))
        }
    }

    @Test
    fun theDownloadsProcessIsKnown() {
        assertEquals(Kind.DOWNLOADS, AppProcesses.kindOf("$pkg:downloads", pkg))
    }

    @Test
    fun theProcessesOfGeckoViewAreNotTheApps() {
        for (suffix in listOf("tab_disable_art_image_9", "isolatedTab_disable_art_image_0", "gpu_disable_art_image_",
                "socket_disable_art_image_", "crashhelper_disable_art_image_", "media", "utility_disable_art_image_")) {
            assertEquals(suffix, Kind.OTHER, AppProcesses.kindOf("$pkg:$suffix", pkg))
        }
    }

    /** A name that could not be read, or one of another package, must not leave the browser uninitialized */
    @Test
    fun unknownNamesAreTakenForTheBrowser() {
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("", pkg))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("java", pkg))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf("com.phlox.tvwebbrowser", pkg))
    }

    @Test
    fun theSuffixesAreThoseOfTheManifest() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val incognito = pm.getActivityInfo(ComponentName(context, IncognitoModeMainActivity::class.java), 0).processName
        val downloads = pm.getServiceInfo(ComponentName(context, DownloadService::class.java), 0).processName
        val browser = pm.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).processName
        val kids = pm.getActivityInfo(ComponentName(context, KidsProfileMainActivity::class.java), 0).processName
        assertEquals(context.packageName + AppProcesses.INCOGNITO_SUFFIX, incognito)
        assertEquals(context.packageName + AppProcesses.KIDS_SUFFIX, kids)
        assertTrue(AppProcesses.isKids(kids, context.packageName))
        assertEquals(context.packageName + AppProcesses.DOWNLOADS_SUFFIX, downloads)
        assertEquals(Kind.BROWSER, AppProcesses.kindOf(browser, context.packageName))
        assertEquals(Kind.BROWSER, AppProcesses.kindOf(incognito, context.packageName))
        assertEquals(Kind.DOWNLOADS, AppProcesses.kindOf(downloads, context.packageName))
    }
}
