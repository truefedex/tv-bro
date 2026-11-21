package com.phlox.tvwebbrowser

import android.content.pm.PackageManager
import com.phlox.tvwebbrowser.AppProcesses.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppProcessesTest {
    private val pkg = "com.phlox.tvwebbrowser.gecko"

    @Test
    fun theProcessOfTheAppIsInitialized() {
        assertEquals(Kind.APP, AppProcesses.kindOf(pkg, pkg))
    }

    @Test
    fun theProcessesOfGeckoViewAreNotTheApps() {
        for (suffix in listOf("tab_disable_art_image_9", "isolatedTab_disable_art_image_0", "gpu_disable_art_image_",
                "socket_disable_art_image_", "crashhelper_disable_art_image_", "media", "utility_disable_art_image_")) {
            assertEquals(suffix, Kind.OTHER, AppProcesses.kindOf("$pkg:$suffix", pkg))
        }
    }

    /** A name that could not be read, or one of another package, must not leave the app uninitialized */
    @Test
    fun unknownNamesAreTakenForTheApp() {
        assertEquals(Kind.APP, AppProcesses.kindOf("", pkg))
        assertEquals(Kind.APP, AppProcesses.kindOf("java", pkg))
        assertEquals(Kind.APP, AppProcesses.kindOf("com.phlox.tvwebbrowser", pkg))
    }

    /**
     * Everything of the app runs in its one process: the browser, the downloads, the home screen channels (the
     * services of GeckoView have processes of their own)
     */
    @Test
    fun theComponentsOfTheAppHaveOneProcess() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS)
        val components = info.activities.orEmpty().toList() + info.services.orEmpty() + info.providers.orEmpty()
        val ours = components.filter { it.name.startsWith("com.phlox.tvwebbrowser.") }
        assertTrue(ours.size > 5)
        for (component in ours) assertEquals(component.name, context.packageName, component.processName)
    }
}
