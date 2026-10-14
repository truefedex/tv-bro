package com.phlox.tvwebbrowser.browser

import android.webkit.MimeTypeMap
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.webengine.DownloadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class DownloadsTest {
    @Test
    fun allFieldsAreCopied() {
        val stream = ByteArrayInputStream(ByteArray(0))
        val download = DownloadRequest(
            url = "https://site.com/files/a.zip",
            referer = "https://site.com/",
            userAgent = "UA",
            cookies = "a=1; b=2",
            fileName = "given.zip",
            mimeType = "application/zip",
            size = 42,
            base64BlobData = "data",
            stream = stream,
            operationAfterDownload = Download.OperationAfterDownload.INSTALL
        ).toDownload()
        assertEquals("https://site.com/files/a.zip", download.url)
        assertEquals("given.zip", download.filename)
        assertEquals("", download.filepath)
        assertEquals("https://site.com/", download.referer)
        assertEquals("UA", download.userAgentString)
        assertEquals("a=1; b=2", download.cookies)
        assertEquals("application/zip", download.mimeType)
        assertEquals(42L, download.size)
        assertEquals("data", download.base64BlobData)
        assertSame(stream, download.stream)
        assertEquals(Download.OperationAfterDownload.INSTALL, download.operationAfterDownload)
    }

    @Test
    fun fileNameIsTakenFromContentDisposition() {
        val download = DownloadRequest(url = "https://site.com/get?id=1", referer = "", userAgent = null,
            contentDisposition = "attachment; filename=\"report.pdf\"", mimeType = "application/pdf").toDownload()
        assertEquals("report.pdf", download.filename)
    }

    @Test
    fun fileNameIsTakenFromUrlWithoutQuery() {
        val download = DownloadRequest(url = "https://site.com/files/video.mp4?token=1", referer = "",
            userAgent = null).toDownload()
        assertEquals("video.mp4", download.filename)
    }

    @Test
    fun givenFileNameWinsOverGuessing() {
        //blob downloads: the page scripts know the real name, the url does not contain it
        val download = DownloadRequest(url = "blob:https://site.com/0d5c6c4e-7c1a", referer = "", userAgent = null,
            fileName = "photo.png", mimeType = "image/png").toDownload()
        assertEquals("photo.png", download.filename)
    }

    @Test
    fun linkDownloadGetsMimeTypeFromExtension() {
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("apk", "application/vnd.android.package-archive")
        val request = DownloadRequest.forLink("https://site.com/app.apk", "https://site.com/", "UA", "sid=1")
        assertEquals("application/vnd.android.package-archive", request.mimeType)
        assertEquals("https://site.com/", request.referer)
        assertEquals("UA", request.userAgent)
        assertEquals("sid=1", request.cookies)
        assertNull(request.fileName)
        assertEquals("app.apk", request.toDownload().filename)
    }
}
