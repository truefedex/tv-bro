package com.phlox.tvwebbrowser.utils

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewSpeachRecognizerResultsBinding

/**
 * Voice input. From Android 11 the app listens itself and shows [VoiceInputPlateView] over the window;
 * before, the recognizer app shows its own screen. Back cancels it: [backCallback] goes to the screen's back
 * dispatcher, above the other callbacks.
 * @param showMessage shows a toast of the shell (an icon and one line), at the place of the plate
 */
class VoiceSearchHelper(
    private val activity: ComponentActivity,
    private val showMessage: (icon: Int, message: String) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var plateView: VoiceInputPlateView? = null
    private lateinit var languageModel: String
    private lateinit var callback: Callback

    /** Enabled while the plate is shown: cancels the voice input, nothing is reported */
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stopListening()
    }

    interface Callback {
        /** [text] is null when nothing was recognized */
        fun onResult(text: String?)
    }

    init {
        //the app stopped listening when it left the screen
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stopListening()
        })
    }

    fun initiateVoiceSearch(callback: Callback,
                            languageModel: String = RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH) {
        this.callback = callback
        this.languageModel = languageModel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            initiateVoiceSearchAndroid11AndNext()
        } else {
            val pm = activity.packageManager
            val activities = pm.queryIntentActivities(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0
            )
            if (activities.size == 0) {
                showInstallVoiceEnginePrompt(activity)
            } else {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    languageModel
                )
                intent.putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.speak))
                try {
                    activity.launchForResult(ActivityResultContracts.StartActivityForResult(), intent) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            callback.onResult(matches?.firstOrNull())
                        }
                    }
                } catch (e: Exception) {
                    showError()
                }
            }
        }
    }

    /** Recognizes speech (a search) for [onText]; tells the user when nothing was recognized */
    fun recognize(onText: (String) -> Unit) {
        initiateVoiceSearch(object : Callback {
            override fun onResult(text: String?) {
                if (text == null) {
                    showCanNotRecognize()
                } else {
                    onText(text)
                }
            }
        })
    }

    /** The toast for a [Callback.onResult] without text */
    fun showCanNotRecognize() {
        showMessage(R.drawable.ic_voice_search_off, activity.getString(R.string.can_not_recognize))
    }

    private fun showError() {
        showMessage(R.drawable.ic_error, activity.getString(R.string.error))
    }

    private fun showInstallVoiceEnginePrompt(activity: Activity) {
        val dialogBuilder = AlertDialog.Builder(activity)
            .setTitle(R.string.app_name)
            .setMessage(R.string.voice_search_not_found)
            .setNeutralButton(android.R.string.ok) { _, _ -> }
        val appPackageName =
            if (Utils.isTV(activity)) "com.google.android.katniss" else "com.google.android.googlequicksearchbox"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName"))
        val activities = activity.packageManager.queryIntentActivities(intent, 0)
        if (activities.size > 0) {
            dialogBuilder.setPositiveButton(R.string.find_in_apps_store) { _, _ ->
                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    showError()
                }
            }
        }
        dialogBuilder.show()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun initiateVoiceSearchAndroid11AndNext() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.launchForResult(ActivityResultContracts.RequestPermission(), Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) {
                    initiateVoiceSearch(callback, languageModel)
                }
            }
            return
        }

        stopListening()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        speechRecognizer = recognizer
        val plate = VoiceInputPlateView(activity)
        plateView = plate
        recognizer.setRecognitionListener(object : RecognitionListenerAdapter() {
            //a recognizer that was stopped may still report
            private val current get() = speechRecognizer === recognizer

            override fun onBeginningOfSpeech() {
                if (current) plate.onSpeechStarted()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrEmpty()) plate.resultText = text
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (current) plate.onRmsChanged(rmsdB)
            }

            override fun onEndOfSpeech() {
                if (current) plate.onSpeechEnded()
            }

            override fun onResults(results: Bundle?) {
                if (!current) return
                stopListening()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                callback.onResult(matches?.firstOrNull())
            }

            override fun onError(error: Int) {
                if (!current) return
                stopListening()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> callback.onResult(null)
                    else -> showError()
                }
            }
        })
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizerIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            languageModel
        )
        speechRecognizerIntent.putExtra(
            RecognizerIntent.EXTRA_PARTIAL_RESULTS,
            true
        )
        speechRecognizerIntent.putExtra(
            RecognizerIntent.EXTRA_CALLING_PACKAGE,
            activity.packageName
        )
        recognizer.startListening(speechRecognizerIntent)

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        activity.addContentView(plate, lp)
        backCallback.isEnabled = true
    }

    /** Stops the recognition in progress, if any, and hides its plate; nothing is reported */
    private fun stopListening() {
        val recognizer = speechRecognizer
        speechRecognizer = null
        recognizer?.destroy()
        plateView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        plateView = null
        backCallback.isEnabled = false
    }

    open class RecognitionListenerAdapter: RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
        }

        override fun onBeginningOfSpeech() {
        }

        override fun onRmsChanged(rmsdB: Float) {
        }

        override fun onBufferReceived(buffer: ByteArray?) {
        }

        override fun onEndOfSpeech() {
        }

        override fun onError(error: Int) {
        }

        override fun onResults(results: Bundle?) {
        }

        override fun onPartialResults(partialResults: Bundle?) {
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
        }
    }

    /**
     * The plate of voice input at the bottom center of the window (where toasts show). It waits for speech
     * (a hint, a pulsing ring), hears it (the words recognized so far, a halo that follows the level of the
     * voice), then recognizes it (the mic goes grey, an arc turns around it).
     */
    class VoiceInputPlateView(context: Context) : FrameLayout(context) {
        private enum class State { WAITING, HEARING, PROCESSING }

        private val vb = ViewSpeachRecognizerResultsBinding.inflate(LayoutInflater.from(context), this)
        private val plateMinWidth = resources.getDimensionPixelSize(R.dimen.ds_voice_plate_min_width)
        private val plateMaxWidth = resources.getDimensionPixelSize(R.dimen.ds_voice_plate_max_width)
        private val sideMargin = resources.getDimensionPixelSize(R.dimen.ds_space_screen_x)
        /** The width of the plate that is not the text: its paddings, the mic and their margins */
        private val textInset = resources.getDimensionPixelSize(R.dimen.ds_voice_mic_area) +
                resources.getDimensionPixelSize(R.dimen.ds_voice_mic_area_margin) +
                resources.getDimensionPixelSize(R.dimen.ds_voice_text_margin) +
                resources.getDimensionPixelSize(R.dimen.ds_voice_plate_padding_end)
        private val hintColor = context.themeColor(R.attr.tbColorDsIcon)
        private val textColor = context.themeColor(R.attr.tbColorDsInk)
        private var state = State.WAITING
        private var spin: ObjectAnimator? = null

        /** What was recognized so far */
        var resultText: String = ""
            set(value) {
                field = value
                if (state == State.WAITING) state = State.HEARING
                render()
            }

        init {
            //the phone window is drawn under the navigation bar
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
                insets
            }
            render()
        }

        fun onSpeechStarted() {
            if (state != State.WAITING) return
            state = State.HEARING
            render()
        }

        fun onSpeechEnded() {
            state = State.PROCESSING
            render()
        }

        fun onRmsChanged(rmsdB: Float) {
            if (state != State.HEARING) return
            val level = ((rmsdB - RMS_MIN) / (RMS_MAX - RMS_MIN)).coerceIn(0f, 1f)
            val scale = 1f + level * HALO_GROWTH
            vb.vHalo.animate().scaleX(scale).scaleY(scale).setDuration(LEVEL_DURATION).start()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            //560dp wide, up to 720dp for a long phrase, narrower on a narrow screen
            val available = MeasureSpec.getSize(widthMeasureSpec) - 2 * sideMargin
            val minWidth = minOf(plateMinWidth, available).coerceAtLeast(0)
            val maxTextWidth = (minOf(plateMaxWidth, available) - textInset).coerceAtLeast(0)
            if (vb.llPlate.minimumWidth != minWidth) vb.llPlate.minimumWidth = minWidth
            if (vb.tvResults.maxWidth != maxTextWidth) vb.tvResults.maxWidth = maxTextWidth
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            //it is added after the window got its insets
            ViewCompat.requestApplyInsets(this)
        }

        override fun onDetachedFromWindow() {
            spin?.cancel()
            spin = null
            vb.vHalo.animate().cancel()
            super.onDetachedFromWindow()
        }

        private fun render() {
            val listening = state != State.PROCESSING
            vb.ivMic.isActivated = listening
            vb.vHalo.isVisible = listening
            vb.vRing.isVisible = state == State.WAITING
            if (state == State.WAITING) {
                vb.vHalo.alpha = WAITING_HALO_ALPHA
                vb.vHalo.scaleX = WAITING_HALO_SCALE
                vb.vHalo.scaleY = WAITING_HALO_SCALE
                if (vb.vRing.animation == null) {
                    vb.vRing.startAnimation(AnimationUtils.loadAnimation(context, R.anim.infinite_fadeinout_anim))
                    vb.vHalo.startAnimation(AnimationUtils.loadAnimation(context, R.anim.infinite_fadeinout_anim))
                }
            } else {
                vb.vRing.clearAnimation()
                vb.vHalo.clearAnimation()
                vb.vHalo.alpha = HEARING_HALO_ALPHA
            }

            if (resultText.isEmpty()) {
                vb.tvResults.setText(R.string.speak_now)
                vb.tvResults.setTextColor(hintColor)
            } else {
                vb.tvResults.text = resultText
                vb.tvResults.setTextColor(textColor)
            }

            vb.ivProgress.isVisible = state == State.PROCESSING
            if (state == State.PROCESSING && spin == null) {
                spin = ObjectAnimator.ofFloat(vb.ivProgress, View.ROTATION, 0f, 360f).apply {
                    duration = SPIN_DURATION
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        }

        companion object {
            //the usual range of SpeechRecognizer levels, in dB
            private const val RMS_MIN = -2f
            private const val RMS_MAX = 10f
            //the halo grows from the 48dp of the mic up to 62dp
            private const val HALO_GROWTH = 14f / 48f
            private const val WAITING_HALO_SCALE = 54f / 48f
            private const val WAITING_HALO_ALPHA = 0.3f
            private const val HEARING_HALO_ALPHA = 0.5f
            private const val LEVEL_DURATION = 100L
            private const val SPIN_DURATION = 1000L
        }
    }
}
