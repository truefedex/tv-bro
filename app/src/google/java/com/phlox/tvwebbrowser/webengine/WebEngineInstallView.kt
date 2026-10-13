package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewWebEngineInstallBinding
import kotlinx.coroutines.CompletableDeferred

/**
 * Full-screen placeholder shown over the activity while a web engine is being downloaded.
 */
class WebEngineInstallView(private val activity: Activity, engineName: String) {
    private val root: ViewGroup = activity.findViewById(android.R.id.content)
    private val vb = ViewWebEngineInstallBinding.inflate(activity.layoutInflater, root, false)
    private var retryDecision: CompletableDeferred<Boolean>? = null

    init {
        vb.tvTitle.text = activity.getString(R.string.web_engine_install_title, engineName)
        val fallbackEngine = WebEngineFactory.getProviders().firstOrNull()?.name ?: ""
        vb.btnUseOtherEngine.text = activity.getString(R.string.web_engine_install_use_other, fallbackEngine)
        vb.btnRetry.setOnClickListener { retryDecision?.complete(true) }
        vb.btnUseOtherEngine.setOnClickListener { retryDecision?.complete(false) }
        root.addView(vb.root)
        vb.root.requestFocus()
    }

    fun showPending() {
        showStatus(activity.getString(R.string.web_engine_install_pending), indeterminate = true)
    }

    fun showProgress(bytesDownloaded: Long, totalBytes: Long) {
        if (totalBytes <= 0) {
            showPending()
            return
        }
        showStatus(activity.getString(R.string.web_engine_install_progress,
            Formatter.formatShortFileSize(activity, bytesDownloaded),
            Formatter.formatShortFileSize(activity, totalBytes)), indeterminate = false)
        vb.progressBar.max = 1000
        vb.progressBar.progress = (bytesDownloaded * 1000 / totalBytes).toInt()
    }

    fun showInstalling() {
        showStatus(activity.getString(R.string.web_engine_install_installing), indeterminate = true)
    }

    fun showRestarting() {
        showStatus(activity.getString(R.string.web_engine_install_restarting), indeterminate = true)
    }

    /**
     * Shows the error and waits for the user's choice.
     * @return true to retry, false to give up and use another engine
     */
    suspend fun askRetry(errorMessage: String): Boolean {
        vb.progressBar.visibility = View.INVISIBLE
        vb.tvStatus.text = errorMessage
        vb.llButtons.visibility = View.VISIBLE
        vb.btnRetry.requestFocus()
        val decision = CompletableDeferred<Boolean>()
        retryDecision = decision
        try {
            return decision.await()
        } finally {
            retryDecision = null
            vb.llButtons.visibility = View.GONE
            vb.root.requestFocus()
        }
    }

    fun dismiss() {
        root.removeView(vb.root)
    }

    private fun showStatus(text: String, indeterminate: Boolean) {
        vb.tvStatus.text = text
        vb.progressBar.visibility = View.VISIBLE
        vb.progressBar.isIndeterminate = indeterminate
    }
}
