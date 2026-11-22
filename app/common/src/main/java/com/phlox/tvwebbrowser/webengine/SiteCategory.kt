package com.phlox.tvwebbrowser.webengine

import androidx.annotation.StringRes
import com.phlox.tvwebbrowser.common.R

/**
 * The categories of sites the site filter blocks, each a list of domains ([url], HaGeZi's DNS blocklists, GPL-3.0,
 * downloaded at run time; a domain stands for its subdomains too). Their order is the index of their list in the
 * filter: append new ones at the end.
 */
enum class SiteCategory(val id: String, @StringRes val title: Int, val url: String?) {
    ADULT("adult", R.string.site_category_adult, "$HAGEZI/nsfw-onlydomains.txt"),
    GAMBLING("gambling", R.string.site_category_gambling, "$HAGEZI/gambling.medium-onlydomains.txt"),
    /** Scams, phishing and malware: Threat Intelligence Feeds, the mini variant (the full one has millions) */
    THREATS("threats", R.string.site_category_threats, "$HAGEZI/tif.mini-onlydomains.txt"),
    /** VPNs, proxies, Tor and encrypted DNS, which would take a child around the filter */
    BYPASS("bypass", R.string.site_category_bypass, "$HAGEZI/doh-vpn-proxy-bypass-onlydomains.txt"),
    /** The list of the address the parent gives */
    CUSTOM("custom", R.string.site_category_custom, null);

    /** Its bit in a mask of categories */
    val bit: Long get() = 1L shl ordinal

    companion object {
        /** The categories with a list of the app, all on by default */
        val listed: List<SiteCategory> get() = entries.filter { it.url != null }

        fun ofId(id: String): SiteCategory? = entries.firstOrNull { it.id == id }
    }
}

private const val HAGEZI = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard"
