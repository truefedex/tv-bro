package com.phlox.tvwebbrowser.profile

import android.content.Context
import com.phlox.tvwebbrowser.AppContainer
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class KidsProfileDataTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun file(dir: File, vararg path: String) = path.fold(dir) { parent, name -> File(parent, name) }
        .also { it.parentFile?.mkdirs(); it.writeText("x") }

    @Test
    fun deletingRemovesTheDatabaseAndTabFilesOfTheProfileOnly() = runBlocking {
        val kidsDb = context.getDatabasePath(AppDatabase.KIDS_DATABASE_NAME).also { it.parentFile?.mkdirs(); it.writeText("x") }
        val kidsWal = File(kidsDb.path + "-wal").also { it.writeText("x") }
        val mainDb = context.getDatabasePath(AppDatabase.DATABASE_NAME).also { it.writeText("x") }
        val kidsState = file(context.filesDir, AppContainer.KIDS_DIR, "wvstates", "a")
        val kidsThumb = file(context.cacheDir, AppContainer.KIDS_DIR, "tabthumbs", "a.webp")
        val mainState = file(context.filesDir, "wvstates", "b")
        assertTrue(KidsProfileData.exists(context))

        KidsProfileData.delete(context)

        assertFalse(KidsProfileData.exists(context))
        for (gone in listOf(kidsDb, kidsWal, kidsState, kidsThumb)) assertFalse(gone.path, gone.exists())
        for (kept in listOf(mainDb, mainState)) assertTrue(kept.path, kept.exists())
    }
}
