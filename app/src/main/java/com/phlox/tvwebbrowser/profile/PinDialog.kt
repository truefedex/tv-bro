package com.phlox.tvwebbrowser.profile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.utils.themeColor

/**
 * Asks for the PIN of the kids profile ([ask]) or lets the user choose one, typed twice ([choose]). The
 * digits are entered with the number keys of a remote or a keyboard, or on a pad of keys for the D-pad;
 * the fourth one ends the entry. The digits entered show as dots only: a TV is watched by the whole room.
 * A dialog with a window of its own, so its keys do not reach the shortcuts of the activity.
 */
class PinDialog private constructor(
    private val activity: AppCompatActivity,
    title: String,
    private val choosing: Boolean,
    private val onAsked: ((Boolean) -> Unit)?,
    private val onChosen: ((String?) -> Unit)?
) {
    companion object {
        private const val DELETE = "⌫"
        private val KEYS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(DELETE, "0", ""))

        /** [onResult] gets true once the right PIN is entered, false when the dialog closes without it */
        fun ask(activity: AppCompatActivity, title: String, onResult: (Boolean) -> Unit) {
            PinDialog(activity, title, choosing = false, onAsked = onResult, onChosen = null).show()
        }

        /** [onChosen] gets the new PIN, or null when the user cancels */
        fun choose(activity: AppCompatActivity, onChosen: (String?) -> Unit) {
            PinDialog(activity, activity.getString(R.string.pin_choose_title), choosing = true, onAsked = null,
                onChosen = onChosen).show()
        }
    }

    private val pin = ProfileLock.get(activity).pin
    private val handler = Handler(Looper.getMainLooper())
    private val entered = StringBuilder()
    /** While choosing: the PIN typed the first time */
    private var first: String? = null
    private var waiting = false
    private var done = false
    private val dialog: AlertDialog
    private val message: TextView
    private val status: TextView
    private val dots: List<View>
    private val keys = ArrayList<View>()

    init {
        val builder = AlertDialog.Builder(activity)
        val context = builder.context
        val resources = context.resources
        val padding = resources.getDimensionPixelSize(R.dimen.ds_phone_form_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.ds_pin_key_gap)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding / 2, padding, 0)
        }
        message = text(context, R.style.Widget_TVBro_Text_Body)
        content.addView(message, wrap())

        val dotSize = resources.getDimensionPixelSize(R.dimen.ds_pin_dot_size)
        val dotsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        dots = List(ProfilePin.LENGTH) {
            View(context).apply { setBackgroundResource(R.drawable.ds_pin_dot) }.also {
                dotsRow.addView(it, LinearLayout.LayoutParams(dotSize, dotSize).apply { marginStart = gap; marginEnd = gap })
            }
        }
        content.addView(dotsRow, wrap().apply { topMargin = padding })

        status = text(context, R.style.Widget_TVBro_Text_Caption).apply { minLines = 2 }
        content.addView(status, wrap().apply { topMargin = padding / 2 })

        val keySize = resources.getDimensionPixelSize(R.dimen.ds_pin_key_size)
        for (row in KEYS) {
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in row) {
                val params = LinearLayout.LayoutParams(keySize, keySize).apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) }
                if (label.isEmpty()) {
                    rowView.addView(View(context), params)
                    continue
                }
                val key = TextView(context, null, 0, R.style.Widget_TVBro_TextButton_Ds).apply {
                    text = label
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    if (label == DELETE) contentDescription = context.getString(R.string.delete)
                    setOnClickListener { if (label == DELETE) delete() else digit(label[0]) }
                }
                keys += key
                rowView.addView(key, params)
            }
            content.addView(rowView, wrap())
        }

        dialog = builder.setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
            .setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                if (!done) {
                    onAsked?.invoke(false)
                    onChosen?.invoke(null)
                }
            }
            .create()
        showStage()
    }

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun text(context: Context, style: Int) = TextView(context, null, 0, style).apply {
        gravity = Gravity.CENTER
        setTextColor(context.themeColor(R.attr.tbColorDsInk))
    }

    private fun show() {
        dialog.show()
        keys.firstOrNull()?.requestFocus()
        if (!choosing) pin.waitLeft().takeIf { it > 0 }?.let(::startWait)
    }

    /** What is asked now: the PIN, a new one, or the new one again */
    private fun showStage() {
        message.setText(when {
            !choosing -> R.string.pin_enter
            first == null -> R.string.pin_choose
            else -> R.string.pin_repeat
        })
    }

    /** The number keys of a remote or a keyboard type the digits, wherever the focus is */
    private fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        val typed = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            else -> null
        }
        if (typed == null && keyCode != KeyEvent.KEYCODE_DEL) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (typed != null) digit(typed) else delete()
        }
        return true
    }

    private fun digit(digit: Char) {
        if (waiting || done || entered.length >= ProfilePin.LENGTH) return
        entered.append(digit)
        if (entered.length == 1) status.text = ""
        showDots()
        if (entered.length == ProfilePin.LENGTH) {
            val code = entered.toString()
            //the last dot shows before the dialog answers
            handler.post { submit(code) }
        }
    }

    private fun delete() {
        if (waiting || done || entered.isEmpty()) return
        entered.deleteCharAt(entered.length - 1)
        showDots()
    }

    private fun showDots() = dots.forEachIndexed { index, dot -> dot.isActivated = index < entered.length }

    private fun clear() {
        entered.clear()
        showDots()
    }

    private fun submit(code: String) {
        if (done) return
        if (choosing) {
            val firstCode = first
            when {
                firstCode == null -> {
                    first = code
                    clear()
                    showStage()
                }
                firstCode == code -> {
                    done = true
                    dialog.dismiss()
                    onChosen?.invoke(code)
                }
                else -> {
                    first = null
                    clear()
                    showStage()
                    status.setText(R.string.pin_mismatch)
                }
            }
            return
        }
        when (val result = pin.check(code)) {
            ProfilePin.Result.Right -> {
                done = true
                dialog.dismiss()
                onAsked?.invoke(true)
            }
            is ProfilePin.Result.Wrong -> {
                clear()
                if (result.waitMs > 0) {
                    startWait(result.waitMs)
                } else {
                    status.text = activity.resources.getQuantityString(R.plurals.pin_wrong_tries_left,
                        result.triesLeft, result.triesLeft)
                }
            }
            is ProfilePin.Result.Wait -> {
                clear()
                startWait(result.waitMs)
            }
        }
    }

    /** A pause after too many wrong tries: the keys rest and the time left counts down */
    private fun startWait(waitMs: Long) {
        if (waitMs <= 0) return
        waiting = true
        keys.forEach { it.isEnabled = false }
        message.setText(R.string.pin_forgot)
        handler.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                val left = pin.waitLeft()
                if (left <= 0) {
                    waiting = false
                    keys.forEach { it.isEnabled = true }
                    status.text = ""
                    showStage()
                    keys.firstOrNull()?.requestFocus()
                    return
                }
                val seconds = (left + 999) / 1000
                status.text = activity.getString(R.string.pin_wait, "%d:%02d".format(seconds / 60, seconds % 60))
                handler.postDelayed(this, 1000)
            }
        }
        tick.run()
    }
}
