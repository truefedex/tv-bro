package com.phlox.tvwebbrowser.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.AttributeSet
import android.view.*
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.BounceInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.DrawableCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.databinding.ViewSpeachRecognizerResultsBinding


class VoiceSearchHelper(private val activity: Activity, private val requestCode: Int, private val permissionRequestCode: Int) {

    private var recognitionResultsRendererView: RecognitionResultsRendererView? = null
    private lateinit var languageModel: String
    private lateinit var callback: Callback

    interface Callback {
        fun onResult(text: String?)
/*        fun onFallbackStartedRecognizing()
        fun onFallbackPartialResult(text: String)
        fun onFallbackError(error: Int)*/
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
                    activity.startActivityForResult(intent, requestCode)
                } catch (e: Exception) {
                    Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                    Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialogBuilder.show()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun initiateVoiceSearchAndroid11AndNext() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequestCode)
            return
        }

        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        speechRecognizer.setRecognitionListener(object : RecognitionListenerAdapter() {
            override fun onReadyForSpeech(params: Bundle?) {
                //callback.onFallbackStartedRecognizing()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    recognitionResultsRendererView?.apply {
                        resultText = matches.first()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                disposeResultsView()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                callback.onResult(matches?.firstOrNull())
            }

            override fun onError(error: Int) {
                disposeResultsView()
                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
            }

            override fun onRmsChanged(rmsdB: Float) {
                recognitionResultsRendererView?.onRmsChanged(rmsdB)
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
        speechRecognizer.startListening(speechRecognizerIntent)

        recognitionResultsRendererView = RecognitionResultsRendererView(activity)
        val lp = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        activity.addContentView(recognitionResultsRendererView, lp)
    }

    private fun disposeResultsView() {
        (recognitionResultsRendererView?.parent as? ViewGroup)?.apply {
            removeView(recognitionResultsRendererView)
            recognitionResultsRendererView = null
        }
    }

    fun processActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != this.requestCode) return false

        if (resultCode == Activity.RESULT_OK) {
            val matches = data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS)

            callback.onResult(matches?.firstOrNull())
        }
        return true
    }

    fun processPermissionsResult(requestCode: Int, permissions: Array<String>,
                                 grantResults: IntArray): Boolean {
        if (requestCode != permissionRequestCode) return false
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initiateVoiceSearch(callback)
        }
        return true
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

    @RequiresApi(Build.VERSION_CODES.O)
    class RecognitionResultsRendererView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr) {

        private var infiniteAnimation = AnimationUtils.loadAnimation(activity, R.anim.infinite_fadeinout_anim)
        private var vb =
            ViewSpeachRecognizerResultsBinding.inflate(LayoutInflater.from(activity), this)
        var resultText: String = ""
        set(value) {
            field = value
            vb.tvResults.text = value
        }
        var minRMSdB = 0f
        var maxRMSdB = 0f

        init {
            setBackgroundResource(R.color.top_bar_background)
            elevation = Utils.D2P(context, 5f)
            infiniteAnimation.interpolator = BounceInterpolator()
            vb.ivMic.startAnimation(infiniteAnimation)
        }

        fun onRmsChanged(rmsdB: Float) {
            vb.ivMic.clearAnimation()
            if (rmsdB > maxRMSdB) maxRMSdB = rmsdB
            if (rmsdB < minRMSdB) minRMSdB = rmsdB
            val frac = (rmsdB - minRMSdB) / (maxRMSdB - minRMSdB)
            vb.ivMic.setColorFilter(Color.argb(1f, 0f, 0.4f * frac, 0.8f * frac), PorterDuff.Mode.SRC_IN)
        }
    }
}