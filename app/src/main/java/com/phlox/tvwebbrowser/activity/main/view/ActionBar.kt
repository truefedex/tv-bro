package com.phlox.tvwebbrowser.activity.main.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View.OnFocusChangeListener
import android.view.View.OnKeyListener
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.activity.downloads.ActiveDownloadsModel
import com.phlox.tvwebbrowser.databinding.ViewActionbarBinding
import com.phlox.tvwebbrowser.utils.Utils
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository

class ActionBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val vb = ViewActionbarBinding.inflate( LayoutInflater.from(context),this)
    var callback: Callback? = null
    private var downloadAnimation: Animation? = null
    private var downloadsModel = ActiveModelsRepository.get(ActiveDownloadsModel::class, context)
    /**
     * The address is edited: the field is selected and its text has the focus. Otherwise the field
     * itself is a focus stop (focused, not edited), so Left and Right move between the bar buttons.
     */
    private var editing = false
    private var textBeforeEditing = ""

    interface Callback {
        fun closeWindow()
        fun navigateBack()
        fun navigateForward()
        /** Stops the page if it is loading, reloads it otherwise */
        fun reloadOrStop()
        fun showDownloads()
        fun showFavorites()
        fun showHistory()
        fun showSettings()
        fun initiateVoiceSearch()
        fun search(text: String)
        fun onUrlInputDone()
        fun toggleIncognitoMode()
    }

    private val etUrlFocusChangeListener = OnFocusChangeListener { _, focused ->
        //leaving the text (to the tabs, or the bar is hidden) cancels the editing
        if (!focused && editing) {
            stopEditing(submitted = false)
        }
    }

    private val etUrlKeyListener = OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    val text = vb.etUrl.text.toString()
                    stopEditing(submitted = true)
                    callback?.search(text)
                    callback?.onUrlInputDone()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    /** Typing on a keyboard while the field is focused starts the editing with the typed character */
    private val addressFieldKeyListener = OnKeyListener { _, _, keyEvent ->
        if (!editing && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.isPrintingKey) {
            startEditing(selectAllLater = false)
            vb.etUrl.selectAll()
            vb.etUrl.dispatchKeyEvent(keyEvent)
            return@OnKeyListener true
        }
        false
    }

    init {
        init()
    }

    fun init() {
        orientation = HORIZONTAL

        if (isInEditMode) return

        val incognitoMode = AppContext.provideConfig().incognitoMode

        vb.ibMenu.setOnClickListener { callback?.closeWindow() }
        vb.ibBack.setOnClickListener { callback?.navigateBack() }
        vb.ibForward.setOnClickListener { callback?.navigateForward() }
        vb.ibRefresh.setOnClickListener { callback?.reloadOrStop() }
        vb.ibDownloads.setOnClickListener { callback?.showDownloads() }
        vb.ibFavorites.setOnClickListener { callback?.showFavorites() }
        vb.ibHistory.setOnClickListener { callback?.showHistory() }
        vb.ibIncognito.setOnClickListener { callback?.toggleIncognitoMode() }
        vb.ibSettings.setOnClickListener { callback?.showSettings() }

        //the groups clip their segments to the rounded border
        vb.llNavigation.clipToOutline = true
        vb.llFunctions.clipToOutline = true

        if (Utils.isFireTV(context)) {
            vb.ibVoiceSearch.visibility = GONE
            vb.vVoiceSearchDivider.visibility = GONE
        } else {
            vb.ibVoiceSearch.setOnClickListener { callback?.initiateVoiceSearch() }
        }

        vb.ibIncognito.isActivated = incognitoMode

        vb.llAddress.setOnClickListener { startEditing(selectAllLater = true) }
        vb.llAddress.setOnKeyListener(addressFieldKeyListener)
        vb.etUrl.onFocusChangeListener = etUrlFocusChangeListener
        vb.etUrl.setOnKeyListener(etUrlKeyListener)


        downloadsModel.activeDownloads.subscribe(context as AppCompatActivity) {
            if (it.isNotEmpty()) {
                if (downloadAnimation == null) {
                    downloadAnimation = AnimationUtils.loadAnimation(context, R.anim.infinite_fadeinout_anim)
                    vb.ibDownloads.startAnimation(downloadAnimation)
                }
            } else {
                downloadAnimation?.apply {
                    this.reset()
                    vb.ibDownloads.clearAnimation()
                    downloadAnimation = null
                }
            }
        }
    }

    /**
     * Back and Forward stay focusable when they can not be used and are only dimmed, so the number
     * of presses to each button never changes. Refresh becomes Stop while the page loads.
     */
    fun setNavigationState(canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) {
        vb.ibBack.imageAlpha = if (canGoBack) OPAQUE else DIMMED
        vb.ibForward.imageAlpha = if (canGoForward) OPAQUE else DIMMED
        vb.ibRefresh.setImageResource(if (loading) R.drawable.ic_stop else R.drawable.ic_refresh)
        vb.ibRefresh.contentDescription = context.getString(if (loading) R.string.stop_loading else R.string.refresh_page)
    }

    fun setAddressBoxText(text: String) {
        val newText = if (text == Config.HOME_PAGE_URL) "" else text
        if (vb.etUrl.text.toString() != newText) {
            vb.etUrl.setText(newText)
        }
    }

    /** The user is editing the address */
    fun isEditingAddress(): Boolean = editing

    fun setAddressBoxTextColor(color: Int) {
        vb.etUrl.setTextColor(color)
    }

    /**
     * @param selectAllLater select the address after the keyboard is shown (some Android TV keyboards
     * drop a selection made before)
     */
    private fun startEditing(selectAllLater: Boolean) {
        if (editing) return
        editing = true
        textBeforeEditing = vb.etUrl.text.toString()
        vb.llAddress.isActivated = true
        vb.etUrl.isFocusable = true
        vb.etUrl.isFocusableInTouchMode = true
        vb.etUrl.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(vb.etUrl, InputMethodManager.SHOW_IMPLICIT)
        if (selectAllLater) {
            postDelayed({ if (editing) vb.etUrl.selectAll() }, 500)
        }
    }

    /**
     * Ends the editing; the field keeps the focus if the text had it.
     * @param submitted false to bring back the address that was there before
     */
    private fun stopEditing(submitted: Boolean) {
        if (!editing) return
        editing = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(vb.etUrl.windowToken, 0)
        if (!submitted) {
            vb.etUrl.setText(textBeforeEditing)
        }
        vb.llAddress.isActivated = false
        if (vb.etUrl.hasFocus()) {
            vb.llAddress.requestFocus()
        }
        vb.etUrl.isFocusable = false
        vb.etUrl.isFocusableInTouchMode = false
    }

    /** Stops editing the address without using it */
    fun cancelEditing() = stopEditing(submitted = false)

    /** The bar opens with the focus on the address field */
    fun catchFocus() {
        vb.llAddress.requestFocus()
    }

    companion object {
        private const val OPAQUE = 255
        /** Disabled icons are drawn at 40% */
        private const val DIMMED = 102
    }
}