package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.DownloadRowText
import com.phlox.tvwebbrowser.activity.main.DownloadsNavigator
import com.phlox.tvwebbrowser.databinding.DialogClearDownloadsBinding

/**
 * Asks whether to clear the list of downloads: the downloads in progress and paused stay, the files stay
 * unless the box is checked. The focus starts on Cancel, so clearing is never one press away.
 */
object ClearDownloadsDialog {
    fun show(context: Context, request: DownloadsNavigator.ClearAllRequest, onConfirm: (deleteFiles: Boolean) -> Unit) {
        val vb = DialogClearDownloadsBinding.inflate(LayoutInflater.from(context))
        val resources = context.resources
        var message = resources.getQuantityString(R.plurals.clear_all_downloads_message, request.count, request.count)
        if (request.activeCount > 0) {
            message += " " + resources.getQuantityString(R.plurals.clear_all_downloads_active,
                request.activeCount, request.activeCount)
        }
        vb.tvMessage.text = message
        vb.cbDeleteFiles.text = context.getString(R.string.also_delete_files) + "\n" +
            context.getString(R.string.files_size_in_downloads, DownloadRowText(context).size(request.filesSize))
        vb.cbDeleteFiles.isChecked = false
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.clear_all_downloads_title)
            .setView(vb.root)
            .setPositiveButton(R.string.clear) { _, _ -> onConfirm(vb.cbDeleteFiles.isChecked) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
    }
}
