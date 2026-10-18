package com.phlox.tvwebbrowser.activity.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.model.Download
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Created by PDT on 24.01.2017.
 */

/** A row of the downloads list (the root of view_download_item); it shows the progress of its download */
class DownloadListItemView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs), ActiveDownloadsModel.Listener {
    private var defaultTextColor: Int = 0
    private var tvTitle: TextView? = null
    private var tvURL: TextView? = null
    private var tvTime: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressBar2: ProgressBar? = null
    private var tvSize: TextView? = null
    /** Where the row gets the updates of downloads while it is attached */
    var updates: DownloadsActivity? = null
    var download: Download? = null
    set(value) {
        field = value
        if (field == null) return
        tvTitle!!.text = field!!.filename
        tvURL!!.text = field!!.url
        tvTime!!.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(field!!.time))
        updateUI(field!!)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tvTitle = findViewById(R.id.tvTitle)
        tvURL = findViewById(R.id.tvURL)
        tvTime = findViewById(R.id.tvTime)
        tvSize = findViewById(R.id.tvSize)
        defaultTextColor = tvSize!!.currentTextColor
        progressBar = findViewById(R.id.progressBar)
        progressBar2 = findViewById(R.id.progressBar2)
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI(download: Download) {
        if (tvTitle?.text != download.filename) {
            tvTitle?.text = download.filename
        }
        this.download?.size = download.size
        this.download?.bytesReceived = download.bytesReceived
        this.download?.status = download.status
        tvSize!!.setTextColor(defaultTextColor)
        if (download.status == Download.Status.CANCELLED) {
            tvSize!!.setText(R.string.cancelled)
            progressBar!!.visibility = View.INVISIBLE
            progressBar2!!.visibility = View.GONE
        } else if (download.status == Download.Status.FAILED) {
            tvSize!!.setText(R.string.error)
            tvSize!!.setTextColor(Color.RED)
            progressBar!!.visibility = View.INVISIBLE
            progressBar2!!.visibility = View.GONE
        } else if (download.size == 0L) {
            tvSize!!.text = Formatter.formatShortFileSize(context, download.bytesReceived)
            progressBar!!.visibility = View.INVISIBLE
            progressBar2!!.visibility = View.VISIBLE
        } else if (download.size > 0) {
            if (download.status == Download.Status.DONE) {
                tvSize!!.text = Formatter.formatShortFileSize(context, download.size)
                progressBar!!.visibility = View.INVISIBLE
                progressBar2!!.visibility = View.GONE
            } else {
                tvSize!!.text = Formatter.formatShortFileSize(context, download.bytesReceived) + "/\n" +
                        Formatter.formatShortFileSize(context, download.size)
                progressBar!!.visibility = View.VISIBLE
                progressBar2!!.visibility = View.GONE
                if (download.bytesReceived > download.size) {
                    download.size = 0L//wrong value from server - ignore it for future updates
                } else {
                    progressBar!!.progress = (download.bytesReceived * 100 / download.size).toInt()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updates?.registerListener(this)
    }

    override fun onDetachedFromWindow() {
        updates?.unregisterListener(this)
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
