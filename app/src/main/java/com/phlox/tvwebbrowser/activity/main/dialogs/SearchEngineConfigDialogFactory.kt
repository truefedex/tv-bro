package com.phlox.tvwebbrowser.activity.main.dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.SpinnerAdapter

import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.MainActivity

import java.util.Arrays

/**
 * Created by fedex on 18.01.17.
 */

object SearchEngineConfigDialogFactory {

    interface Callback {
        fun onDone(url: String)
    }

    fun show(context: Context, selectedUrl: String, prefs: SharedPreferences, cancellable: Boolean, callback: Callback) {
        val enginesTitles = arrayOf("Google", "Bing", "Yahoo!", "DuckDuckGo", "Yandex", "Custom")
        val enginesURLs = Arrays.asList("https://www.google.com/search?q=[query]", "https://www.bing.com/search?q=[query]",
                "https://search.yahoo.com/search?p=[query]", "https://duckduckgo.com/?q=[query]",
                "https://yandex.com/search/?text=[query]", "")

        var selected = 0
        if ("" != selectedUrl) {
            selected = enginesURLs.indexOf(selectedUrl)
        }

        val builder = AlertDialog.Builder(context)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_search_engine, null)
        val etUrl = view.findViewById<View>(R.id.etUrl) as EditText
        val llUrl = view.findViewById<View>(R.id.llURL) as LinearLayout

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, enginesTitles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val spEngine = view.findViewById<View>(R.id.spEngine) as Spinner
        spEngine.adapter = adapter

        if (selected != -1) {
            spEngine.setSelection(selected)
            etUrl.setText(enginesURLs[selected])
        } else {
            spEngine.setSelection(enginesTitles.size - 1)
            llUrl.visibility = View.VISIBLE
            etUrl.setText(selectedUrl)
            etUrl.requestFocus()
        }
        spEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == enginesTitles.size - 1 && llUrl.visibility == View.GONE) {
                    llUrl.visibility = View.VISIBLE
                    llUrl.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    etUrl.requestFocus()
                }
                etUrl.setText(enginesURLs[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }

        builder.setView(view)
                .setCancelable(cancellable)
                .setTitle(R.string.engine)
                .setPositiveButton(R.string.save) { dialog, which ->
                    val url = etUrl.text.toString()
                    val editor = prefs.edit()
                    editor.putString(MainActivity.SEARCH_ENGINE_URL_PREF_KEY, url)
                    editor.apply()
                    callback.onDone(url)
                }
                .show()
    }
}
