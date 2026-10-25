package com.phlox.tvwebbrowser.utils

import com.phlox.tvwebbrowser.utils.DownloadFiles.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadFilesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun kindIsToldByThePath() {
        assertNull(DownloadFiles.kindOf(""))
        assertEquals(Kind.FILE, DownloadFiles.kindOf("/storage/emulated/0/Download/a.mp4"))
        assertEquals(Kind.MEDIA_STORE, DownloadFiles.kindOf("content://media/external_primary/downloads/42"))
        assertEquals(Kind.DOCUMENT, DownloadFiles.kindOf(
            "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fa.mp4"))
        //a provider whose name starts like MediaStore's is not MediaStore
        assertEquals(Kind.DOCUMENT, DownloadFiles.kindOf("content://media.example/document/1"))
    }

    @Test
    fun filesOfTheDownloadsDirectory() {
        val file = File.createTempFile("download", ".bin")
        //paths of Android start with "/", Windows paths of the test machine do not
        assumeTrue(file.absolutePath.startsWith("/"))
        assertTrue(DownloadFiles.exists(context, file.absolutePath))
        DownloadFiles.delete(context, file.absolutePath)
        assertFalse(file.exists())
        assertFalse(DownloadFiles.exists(context, file.absolutePath))
        assertNull(DownloadFiles.uriOf(context, file.absolutePath))
        assertFalse(DownloadFiles.exists(context, ""))
        assertNull(DownloadFiles.uriOf(context, ""))
    }
}
