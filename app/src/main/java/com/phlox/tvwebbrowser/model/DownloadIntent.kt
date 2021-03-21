package com.phlox.tvwebbrowser.model

data class DownloadIntent(val url: String, val referer: String, var fileName: String,
                          val userAgent: String, val mimeType: String?,
                          var operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
                            var fullDestFilePath: String? = null, val base64BlobData: String? = null)