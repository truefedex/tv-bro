package com.phlox.tvwebbrowser.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.phlox.tvwebbrowser.R

object VoiceSearchHelper {

    fun initiateVoiceSearch(activity: Activity, requestCode: Int, languageModel: String = RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH) {
        val pm = activity.packageManager
        var activities = pm.queryIntentActivities(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        if (activities.size == 0) {
            val dialogBuilder = AlertDialog.Builder(activity)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.voice_search_not_found)
                    .setNeutralButton(android.R.string.ok) { _, _ ->  }
            val appPackageName = if (Utils.isTV(activity)) "com.google.android.katniss" else "com.google.android.googlequicksearchbox"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName"))
            activities = pm.queryIntentActivities(intent, 0)
            if (activities.size > 0) {
                dialogBuilder.setPositiveButton(R.string.find_in_apps_store) { _, _ ->
                    try {
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialogBuilder.show()
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    languageModel)
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.speak))
            try {
                activity.startActivityForResult(intent, requestCode)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}