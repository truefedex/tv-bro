package com.phlox.tvwebbrowser.activity.downloads

import android.content.Context
import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.service.downloads.DownloadService

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Created by PDT on 24.01.2017.
 */

class DownloadListItemView(private val downloadsActivity: DownloadsActivity, private val viewType: Int) : FrameLayout(downloadsActivity), DownloadService.Listener {
    private var defaultTextColor: Int = 0
    private var tvDate: TextView? = null
    private var tvTitle: TextView? = null
    private var tvURL: TextView? = null
    private var tvTime: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressBar2: ProgressBar? = null
    private var tvSize: TextView? = null
    var download: Download? = null
    set(value) {
        field = value
        if (field == null) return
        when (viewType) {
            DownloadListAdapter.VIEW_TYPE_HEADER -> {
                val df = SimpleDateFormat.getDateInstance()
                tvDate!!.text = df.format(Date(field!!.time))
            }
            DownloadListAdapter.VIEW_TYPE_DOWNLOAD_ITEM -> {
                tvTitle!!.text = field!!.filename
                tvURL!!.text = field!!.url
                val sdf = SimpleDateFormat("HH:mm")
                tvTime!!.text = sdf.format(Date(field!!.time))
                updateUI(field!!)
            }
        }
    }

    init {
        LayoutInflater.from(downloadsActivity).inflate(
                if (viewType == DownloadListAdapter.VIEW_TYPE_HEADER)
                    R.layout.view_history_header_item
                else
                    R.layout.view_download_item, this)
        when (viewType) {
            DownloadListAdapter.VIEW_TYPE_HEADER -> tvDate = findViewById(R.id.tvDate)
            DownloadListAdapter.VIEW_TYPE_DOWNLOAD_ITEM -> {
                tvTitle = findViewById(R.id.tvTitle)
                tvURL = findViewById(R.id.tvURL)
                tvTime = findViewById(R.id.tvTime)
                tvSize = findViewById(R.id.tvSize)
                defaultTextColor = tvSize!!.currentTextColor
                progressBar = findViewById(R.id.progressBar)
                progressBar2 = findViewById(R.id.progressBar2)
            }
        }
    }

    private fun updateUI(download: Download) {
        this.download?.size = download.size
        this.download?.bytesReceived = download.bytesReceived
        progressBar!!.visibility = View.INVISIBLE
        progressBar2!!.visibility = View.GONE
        tvSize!!.setTextColor(defaultTextColor)
        if (download.size == Download.CANCELLED_MARK) {
            tvSize!!.setText(R.string.cancelled)
        } else if (download.size == Download.BROKEN_MARK) {
            tvSize!!.setText(R.string.error)
            tvSize!!.setTextColor(Color.RED)
        } else if (download.size == 0L) {
            tvSize!!.text = Formatter.formatShortFileSize(context, download.bytesReceived)
            progressBar2!!.visibility = View.VISIBLE
        } else if (download.size > 0) {
            if (download.size == download.bytesReceived) {
                tvSize!!.text = Formatter.formatShortFileSize(context, download.size)
            } else {
                tvSize!!.text = Formatter.formatShortFileSize(context, download.bytesReceived) + "/\n" +
                        Formatter.formatShortFileSize(context, download.size)
                progressBar!!.visibility = View.VISIBLE
                progressBar!!.progress = (download.bytesReceived * 100 / download.size).toInt()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        downloadsActivity.registerListener(this)
    }

    override fun onDetachedFromWindow() {
        downloadsActivity.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onDownloadUpdated(downloadInfo: Download) {
        if (downloadInfo.id == this.download?.id) {
            updateUI(downloadInfo)
        }
    }

    override fun onDownloadError(downloadInfo: Download, responseCode: Int, responseMessage: String) {
        if (downloadInfo.id == this.download?.id) {
            updateUI(downloadInfo)
        }
    }

    override fun onAllDownloadsComplete() {}
}
