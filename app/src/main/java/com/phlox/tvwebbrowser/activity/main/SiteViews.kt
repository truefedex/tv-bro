package com.phlox.tvwebbrowser.activity.main

/** Positions of the site views in R.array.site_views */
const val SITE_VIEW_DESKTOP = 0
const val SITE_VIEW_MOBILE = 1

fun siteViewPosition(desktop: Boolean): Int = if (desktop) SITE_VIEW_DESKTOP else SITE_VIEW_MOBILE
