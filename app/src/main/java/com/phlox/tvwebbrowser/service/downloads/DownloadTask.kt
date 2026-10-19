package com.phlox.tvwebbrowser.service.downloads

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.utils.DownloadFileActions
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection

/**
 * Created by PDT on 23.01.2017.
 */

/**
 * A download running in [DownloadService], on a thread of its own: copies the input to the file
 * of the [download], writes the progress to the database and ends with the final state there.
 */
abstract class DownloadTask(val download: Download, private val listener: Listener) : Runnable {
    companion object {
        val TAG: String = DownloadTask::class.java.simpleName
        private const val BUFFER_SIZE = 64 * 1024
        /** How often the progress is written to the database */
        const val PROGRESS_INTERVAL = 1000L
    }

    interface Listener {
        /** On the download thread, every [PROGRESS_INTERVAL] at most */
        fun onProgress(task: DownloadTask)
        /** On the download thread, when the final state is written */
        fun onEnded(task: DownloadTask)
    }

    /** Values of [stopRequest] */
    object Stop {
        const val NONE = 0
        const val CANCEL = 1
        const val PAUSE = 2
        /** The system stops the service, it ran too long */
        const val TIMEOUT = 3
    }

    /** Why the download was asked to stop, one of [Stop] */
    @Volatile
    var stopRequest = Stop.NONE
        private set

    /** Asks the download to stop; the first reason given wins */
    fun stop(reason: Int) {
        if (stopRequest == Stop.NONE) stopRequest = reason
    }

    private val repository get() = TVBro.container.data.downloads

    /** Opens the input, filling what the response tells about the file (its size, name, type) */
    protected abstract fun openInput(): InputStream

    /** The input is the file from this byte: the file already has the bytes before it */
    protected var startOffset = 0L

    /** Called when the input is read to its end, may throw if it was cut */
    protected open fun checkInputComplete() {}

    protected open fun closeInput() {}

    override fun run() {
        var error: String? = null
        var output: OutputStream? = null
        try {
            openInput().use { input ->
                output = try {
                    if (download.filepath.isEmpty()) openOutput(download) else reopenOutput(download, startOffset)
                } catch (e: Exception) {
                    throw StorageException(e)
                }
                download.bytesReceived = startOffset
                runBlocking { repository.update(download) }
                copy(input, output!!)
            }
            if (stopRequest == Stop.NONE) checkInputComplete()
        } catch (e: HttpException) {
            error = Download.Error.http(e.code)
        } catch (e: StorageException) {
            Log.e(TAG, "Can not write ${download.filename}", e)
            error = Download.Error.STORAGE
        } catch (e: IOException) {
            Log.w(TAG, "Download of ${download.url} failed", e)
            error = Download.Error.CONNECTION
        } catch (e: Exception) {
            Log.e(TAG, "Download of ${download.url} failed", e)
            error = e.message ?: e.javaClass.simpleName
        } finally {
            try {
                output?.close()
            } catch (e: IOException) {
                if (error == null && stopRequest == Stop.NONE) error = Download.Error.STORAGE
            }
            closeInput()
        }
        finish(error)
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        var lastProgressTime = System.currentTimeMillis()
        while (stopRequest == Stop.NONE) {
            val count = input.read(buffer)
            if (count == -1) break
            try {
                output.write(buffer, 0, count)
            } catch (e: IOException) {
                throw StorageException(e)
            }
            download.bytesReceived += count
            val now = System.currentTimeMillis()
            if (now - lastProgressTime >= PROGRESS_INTERVAL) {
                lastProgressTime = now
                runBlocking { repository.updateProgress(download.id, download.bytesReceived, download.size) }
                listener.onProgress(this)
            }
        }
    }

    private fun finish(error: String?) {
        val stopped = stopRequest == Stop.PAUSE || stopRequest == Stop.TIMEOUT
        //a resumable download that stops or loses its connection is paused: its file is kept
        val canResume = download.resumable && download.bytesReceived > 0
        val status = when {
            stopRequest == Stop.CANCEL -> Download.Status.CANCELLED
            (stopped || error == Download.Error.CONNECTION) && canResume -> Download.Status.PAUSED
            stopped || error != null -> Download.Status.FAILED
            else -> Download.Status.DONE
        }
        download.status = status
        download.error = when {
            status == Download.Status.DONE || status == Download.Status.CANCELLED -> null
            stopRequest == Stop.TIMEOUT -> Download.Error.TIMEOUT
            stopRequest == Stop.PAUSE -> if (status == Download.Status.PAUSED) null else Download.Error.INTERRUPTED
            else -> error
        }
        when (status) {
            Download.Status.DONE -> {
                download.size = download.bytesReceived
                publish(download)
            }
            //the file is kept to resume the download
            Download.Status.PAUSED -> {}
            else -> DownloadFileActions.deleteFile(AppContext.get(), download)
        }
        runBlocking { repository.update(download) }
        listener.onEnded(this)
    }
}

/**
 * Downloads [Download.url] itself; [resume] continues from the bytes the file has (the whole file is
 * downloaded again if the server can not give the rest, or the file is gone)
 */
class HttpDownloadTask(download: Download, listener: Listener, private val resume: Boolean = false) :
    DownloadTask(download, listener) {
    private var connection: HttpURLConnection? = null

    override fun openInput(): InputStream {
        val offset = if (resume && download.bytesReceived > 0 && fileExists(download)) download.bytesReceived else 0L
        if (offset == 0L && resume && !fileExists(download)) download.filepath = ""
        val response = HttpDownloads.open(download, offset)
        connection = response.connection
        startOffset = response.start
        return response.input
    }

    override fun closeInput() {
        connection?.disconnect()
    }
}

/**
 * Copies the file from a pipe the browser process fills: the response a web engine fetched itself
 * (GeckoView) or the data of a blob
 */
class StreamDownloadTask(download: Download, private val pipe: ParcelFileDescriptor, listener: Listener) :
    DownloadTask(download, listener) {

    override fun openInput(): InputStream = FileInputStream(pipe.fileDescriptor)

    override fun checkInputComplete() {
        //throws if the browser process died or failed to read the response
        pipe.checkError()
    }

    override fun closeInput() {
        try {
            pipe.close()
        } catch (ignored: IOException) {
        }
    }
}

private class StorageException(cause: Throwable) : IOException(cause)

/**
 * Creates the file of the download: a pending item of MediaStore Downloads on Android 11+ (other
 * apps see it when it is complete), a file with a free name in the public Downloads directory before.
 */
private fun openOutput(download: Download): OutputStream {
    val context = AppContext.get()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            if (download.filename.isNotEmpty()) put(MediaStore.Downloads.DISPLAY_NAME, download.filename)
            download.mimeType?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.DOWNLOAD_URI, download.url)
            put(MediaStore.Downloads.REFERER_URI, download.refererHeader)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Can not create the file")
        download.filepath = uri.toString()
        //MediaStore renames the file if the name is taken
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) download.filename = it.getString(0)
        }
        val fd = resolver.openFileDescriptor(uri, "w") ?: throw IOException("Can not open the file")
        return ParcelFileDescriptor.AutoCloseOutputStream(fd)
    }
    if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
        throw IOException("The storage is not mounted")
    }
    @Suppress("DEPRECATION")
    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!directory.exists() && !directory.mkdirs()) throw IOException("Can not create $directory")
    val file = freeFile(directory, download.filename.ifEmpty { "download" })
    download.filename = file.name
    download.filepath = file.absolutePath
    return FileOutputStream(file)
}

/**
 * Opens the file of a download that had started, its bytes from [offset] on dropped (the rest is written
 * again); on Android 11+ it stays pending a while longer.
 */
private fun reopenOutput(download: Download, offset: Long): OutputStream {
    val fd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val resolver = AppContext.get().contentResolver
        val uri = Uri.parse(download.filepath)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }, null, null)
        resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Can not open the file")
    } else {
        ParcelFileDescriptor.open(File(download.filepath),
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
    }
    val output = ParcelFileDescriptor.AutoCloseOutputStream(fd)
    output.channel.truncate(offset)
    output.channel.position(offset)
    return output
}

private fun fileExists(download: Download): Boolean {
    if (download.filepath.isEmpty()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return File(download.filepath).exists()
    return try {
        AppContext.get().contentResolver.query(Uri.parse(download.filepath), arrayOf(MediaStore.Downloads._ID),
            null, null, null)?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        false
    }
}

/** `name.ext`, else `name_(1).ext` and so on */
private fun freeFile(directory: File, fileName: String): File {
    val extPos = fileName.lastIndexOf('.')
    val prefix = if (extPos > 0) fileName.substring(0, extPos) else fileName
    val ext = if (extPos > 0) fileName.substring(extPos) else ""
    var file = File(directory, fileName)
    var i = 0
    while (file.exists()) {
        i++
        file = File(directory, "${prefix}_($i)$ext")
    }
    return file
}

/** Makes the complete file visible to other apps */
private fun publish(download: Download) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && download.filepath.isNotEmpty()) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        try {
            AppContext.get().contentResolver.update(Uri.parse(download.filepath), values, null, null)
        } catch (e: Exception) {
            Log.e(DownloadTask.TAG, "Can not publish ${download.filepath}", e)
        }
    }
}
