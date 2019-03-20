package com.phlox.tvwebbrowser.activity.main.dialogs

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast

import com.phlox.asql.ASQL
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx

import java.util.Arrays

/**
 * Created by PDT on 22.01.2017.
 */

object UserAgentConfigDialogFactory {

    interface Callback {
        fun onDone(defaultUAString: String?)
    }

    fun show(context: Context, selectedUAString: String, callback: Callback) {
        val titles = arrayOf("TV Bro", "Chrome (Desktop)", "Chrome (Mobile)", "Chrome (Tablet)", "Firefox (Desktop)", "Firefox (Tablet)", "Edge (Desktop)", "Safari (Desktop)", "Safari (iPad)", "Apple TV", "Custom")
        val uaStrings = Arrays.asList("",
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.87 Safari/537.36",
                "Mozilla/5.0 (Linux; Android 6.0.1; TV Build/DDD00D) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.98 Mobile Safari/537.36",
                "Mozilla/5.0 (Linux; Android 7.0; TV Build/DDD00D; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/52.0.2743.98 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:50.0) Gecko/20100101 Firefox/50.0",
                "Mozilla/5.0 (Android 4.4; Tablet; rv:41.0) Gecko/41.0 Firefox/41.0",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/42.0.2311.135 Safari/537.36 Edge/12.246",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_2) AppleWebKit/601.3.9 (KHTML, like Gecko) Version/9.0.2 Safari/601.3.9",
                "Mozilla/5.0 (iPad; CPU OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53",
                "AppleTV5,3/9.1.1",
                "")

        var selected = 0
        if ("" != selectedUAString) {
            selected = uaStrings.indexOf(selectedUAString)
        }

        val builder = AlertDialog.Builder(context)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_user_agent_config, null)
        val etUAString = view.findViewById<View>(R.id.etUAString) as EditText
        val llUAString = view.findViewById<View>(R.id.llUAString) as LinearLayout

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, titles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val spTitles = view.findViewById<View>(R.id.spTitles) as Spinner
        spTitles.adapter = adapter

        if (selected != -1) {
            spTitles.setSelection(selected, false)
            etUAString.setText(uaStrings[selected])
        } else {
            spTitles.setSelection(titles.size - 1, false)
            llUAString.visibility = View.VISIBLE
            etUAString.setText(selectedUAString)
            etUAString.requestFocus()
        }
        spTitles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == titles.size - 1 && llUAString.visibility == View.GONE) {
                    llUAString.visibility = View.VISIBLE
                    llUAString.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
                    etUAString.requestFocus()
                }
                etUAString.setText(uaStrings[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }

        builder.setView(view)
                .setCancelable(true)
                .setTitle(R.string.user_agent_string)
                .setPositiveButton(R.string.save) { dialog, which ->
                    val userAgent = etUAString.text.toString().trim { it <= ' ' }
                    if ("" == userAgent) {
                        callback.onDone(WebViewEx.defaultUAString)
                    } else {
                        callback.onDone(userAgent)
                    }
                }
                .show()
    }
}
