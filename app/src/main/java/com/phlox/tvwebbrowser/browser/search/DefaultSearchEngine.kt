package com.phlox.tvwebbrowser.browser.search

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.phlox.tvwebbrowser.Config
import java.util.Locale
import java.util.TimeZone

/** The country the device is in, without the network: an upper-case ISO 3166 code */
object RegionDetector {
    private const val TAG = "RegionDetector"

    /**
     * The country of the SIM card or of the mobile network (phones), else that of the time zone of the system
     * (TVs set it from the network), else that of the locale; null when none is known
     */
    fun country(context: Context): String? {
        try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephony != null) {
                valid(telephony.simCountryIso)?.let { return it }
                valid(telephony.networkCountryIso)?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No country of the telephony", e)
        }
        timeZoneCountry(TimeZone.getDefault().id)?.let { return it }
        return valid(Locale.getDefault().country)
    }

    /** The country of the time zone [id] (Europe/Paris: FR), null for zones of no country (UTC) */
    fun timeZoneCountry(id: String): String? = try {
        valid(android.icu.util.TimeZone.getRegion(id))
    } catch (e: Exception) {
        null
    }

    /** Two letters in upper case; null for other codes (001 of the world, ZZ unknown) */
    fun valid(code: String?): String? {
        val country = code?.trim()?.uppercase(Locale.ROOT) ?: return null
        return country.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "ZZ" }
    }
}

/**
 * Qwant is a partner: in builds with its partner ID ([Config.QWANT_PARTNER]) it is the default search engine in
 * the countries of the contract, for users who have not chosen another engine than the old default (Google).
 */
object DefaultSearchEngine {
    val PARTNER_COUNTRIES = setOf("FR", "GB", "DE", "BE", "NL", "ES", "IT", "AT", "CH", "PL", "SE", "DK", "NO", "IE",
        "US", "CA", "HU", "CZ", "AU")

    /** The index in [Config.SearchEnginesURLs] of the default search engine for the [country] */
    fun defaultEngineIndex(country: String?, partner: Boolean = Config.QWANT_PARTNER): Int =
        if (partner && country != null && country.uppercase(Locale.ROOT) in PARTNER_COUNTRIES) Config.QWANT_INDEX else 0

    /**
     * Chooses the default search engine for the [country] once per [Config.DEFAULT_SEARCH_ENGINE_VERSION], for a
     * user who has none saved or the old default; the user's own choice stays. Nothing happens in builds without the
     * partner ID, so a release built without it by mistake does not use the choice up.
     */
    fun applyOnce(config: Config, country: () -> String?, partner: Boolean = Config.QWANT_PARTNER) {
        if (!partner) return
        if (config.defaultSearchEngineVersion >= Config.DEFAULT_SEARCH_ENGINE_VERSION) return
        config.defaultSearchEngineVersion = Config.DEFAULT_SEARCH_ENGINE_VERSION
        if (config.searchEngineSaved && config.searchEngineURL.value != Config.SearchEnginesURLs[0]) return
        val index = defaultEngineIndex(country(), partner)
        if (index != 0) config.setSearchEngine(Config.SearchEnginesURLs[index])
    }

    fun applyOnce(config: Config, context: Context) = applyOnce(config, { RegionDetector.country(context) })
}
