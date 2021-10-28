package com.phlox.tvwebbrowser.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import java.io.Serializable

data class DownloadIntent(val url: String, val referer: String, var fileName: String,
                          val userAgent: String, val mimeType: String?,
                          var operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
                            var fullDestFilePath: String? = null, val base64BlobData: String? = null) : Serializable {
  fun toAndroidIntent(context: Context): Intent {
    val result = Intent(context, DownloadService::class.java)
    result.putExtra("download", this)
    return result
  }

  companion object {
    fun fromAndroidIntent(intent: Intent): DownloadIntent {
      return intent.extras!!.getSerializable("download") as DownloadIntent
    }
  }
}