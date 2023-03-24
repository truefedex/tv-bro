package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import org.mozilla.geckoview.GeckoView


open class GeckoViewEx @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GeckoView(context, attrs) {
    override fun dispatchDraw(canvas: Canvas?) {
        super.dispatchDraw(canvas)
    }
}