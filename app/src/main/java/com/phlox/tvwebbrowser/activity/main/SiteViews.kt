package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.webengine.SiteView

/** The site views in the order of R.array.site_views */
val SITE_VIEWS = listOf(SiteView.DESKTOP, SiteView.TABLET, SiteView.MOBILE)

fun siteViewPosition(view: SiteView): Int = SITE_VIEWS.indexOf(view)
