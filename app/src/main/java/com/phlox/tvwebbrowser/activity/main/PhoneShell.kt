package com.phlox.tvwebbrowser.activity.main

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.browser.BrowserUiEvent
import com.phlox.tvwebbrowser.browser.CurrentTabState
import com.phlox.tvwebbrowser.databinding.ActivityMainPhoneBinding
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import com.phlox.tvwebbrowser.webengine.virtualCursor
import com.phlox.tvwebbrowser.widgets.NotificationView
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate

/**
 * The phone look: touch only (no virtual cursor), the page between the system bars, any orientation.
 */
class PhoneShell(private val host: BrowserShell.Host) : BrowserShell {
    private val activity = host.activity
    private val controller = host.controller
    private val vb = ActivityMainPhoneBinding.inflate(activity.layoutInflater)

    override val root: View get() = vb.root
    override val tabsContainer: ViewGroup get() = vb.flWebViewContainer
    override val screenOrientation: Int get() = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    init {
        //the window is drawn edge to edge: the content keeps out of the system bars and the keyboard
        ViewCompat.setOnApplyWindowInsetsListener(vb.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /** Lowest priority: back in the page, or out of the app (to the launcher) when it has no history */
    private val pageBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!controller.goBack()) {
                onNoBackHistory()
            }
        }
    }

    override fun registerBackCallbacks(dispatcher: OnBackPressedDispatcher, fullscreen: OnBackPressedCallback) {
        //the enabled callback added last goes first
        dispatcher.addCallback(activity, pageBackCallback)
        dispatcher.addCallback(activity, fullscreen)
    }

    override fun showBusy(busy: Boolean) {
        vb.progressBarGeneric.visibility = if (busy) View.VISIBLE else View.GONE
    }

    override fun onTabsLoaded() {}

    /** Touch only: the page gets the touches, the virtual cursor is never shown */
    override fun onTabViewCreated(tab: BrowserTab) {
        tab.webEngine.virtualCursor?.setVirtualCursorMode(false)
    }

    override fun render(state: CurrentTabState) {}

    override fun onUiEvent(event: BrowserUiEvent) {
        when (event) {
            is BrowserUiEvent.Progress -> {
                vb.progressBar.visibility = if (event.value < 100) View.VISIBLE else View.GONE
                vb.progressBar.progress = event.value
            }
            is BrowserUiEvent.PopupBlocked -> {
                val msg = activity.getString(if (event.newTab) R.string.new_tab_blocked else R.string.popup_dialog_blocked)
                NotificationView.show(vb.rlRoot, R.drawable.ic_block_popups, msg)
            }
            else -> {}
        }
    }

    /** Phones have the system Back and Home: no history leaves the app without closing it */
    override fun onNoBackHistory() {
        activity.moveTaskToBack(true)
    }

    override fun showPage() {}

    override fun onDownloadStarted() {}

    /** A long press on a link: the actions for the link (there is no cursor menu) */
    override fun showContextMenu(
        tab: BrowserTab, uiHost: EngineUiHost, cursorDrawer: CursorDrawerDelegate,
        baseUri: String?, linkUri: String?, srcUri: String?,
        title: String?, altText: String?, textContent: String?, x: Int, y: Int
    ) {
        uiHost.suggestActionsForLink(baseUri, linkUri, srcUri, title, altText, textContent, x, y)
    }
}
