package com.phlox.tvwebbrowser.browser

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.webengine.EngineUiHost
import java.lang.reflect.Proxy

class FakeTabViewHost(context: Context) : BrowserController.TabViewHost {
    override val tabsContainer: ViewGroup = FrameLayout(context)
    var canCreateViews = true
    /** Calls of [EngineUiHost] methods by name */
    val uiHostCalls = ArrayList<String>()

    override fun createTabView(tab: WebTabState): View? =
        if (canCreateViews) tab.webEngine.getOrCreateView(tabsContainer.context) else null

    override fun uiHost(tab: WebTabState): EngineUiHost =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EngineUiHost::class.java)) { _, method, _ ->
            uiHostCalls += method.name
            null
        } as EngineUiHost
}
