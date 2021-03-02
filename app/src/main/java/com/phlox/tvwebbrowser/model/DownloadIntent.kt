package com.phlox.tvwebbrowser.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadIntent(val url: String, val referer: String, var fileName: String,
                          val userAgent: String, val mimeType: String?,
                          var operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
                            var fullDestFilePath: String? = null) : Parcelable