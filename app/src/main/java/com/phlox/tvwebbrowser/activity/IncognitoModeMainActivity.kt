package com.phlox.tvwebbrowser.activity

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity

//Same as MainActivity but runs in separate process
//and store all WebView data separately
class IncognitoModeMainActivity: MainActivity() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        if (!config.incognitoModeHintSuppress) {
            showIncognitoModeHintDialog()
        }
    }

    private fun showIncognitoModeHintDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.incognito_mode)
            .setIcon(R.drawable.ic_incognito)
            .setMessage(R.string.incognito_mode_hint)
            .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(R.string.don_t_show_again) { dialog, _ ->
                config.incognitoModeHintSuppress = true
                dialog.dismiss()
            }
            .show()
    }
}