package com.phlox.tvwebbrowser.adblock.parser

import java.util.TreeMap

/** What the parser read in a list: the numbers of filters, the skipped lines by reason, the header */
class ParseStats {
    var lines = 0
        internal set
    /** Comments, the header and empty lines */
    var comments = 0
        internal set
    var network = 0
        internal set
    var cosmetic = 0
        internal set
    /** The skipped filters by reason ([SkipReason], `option:<name>` for an unsupported option) */
    val skipped: Map<String, Int> get() = skippedByReason
    val skippedTotal: Int get() = skippedByReason.values.sum()

    /** From the header of the list (`! Title:`, `! Version:`, `! Expires:`) */
    var title: String? = null
        internal set
    var version: String? = null
        internal set
    /** How often the list asks to be updated, in hours */
    var expiresHours: Int? = null
        internal set

    private val skippedByReason = TreeMap<String, Int>()

    internal fun skip(reason: String) {
        skippedByReason[reason] = (skippedByReason[reason] ?: 0) + 1
    }

    override fun toString() = "lines $lines, network $network, cosmetic $cosmetic, comments $comments, " +
            "skipped $skippedTotal $skippedByReason"
}

/** Why a filter was skipped (besides `option:<name>`) */
object SkipReason {
    /** Scripts injected into pages: `##+js(…)`, `#%#`, `#$#` (snippets of Adblock Plus) */
    const val SCRIPTLET = "scriptlet"
    /** Procedural element hiding: `#?#`, `:has(`, `:-abp-has(`, `:xpath(`… */
    const val PROCEDURAL = "procedural"
    /** CSS injection of AdGuard (`#$#…{…}`) and uBlock Origin (`:style(`) */
    const val CSS_INJECTION = "css-injection"
    /** HTML filtering: `##^…`, `$$`, `$@$` */
    const val HTML = "html-filter"
    /** Entities (`example.*`) and regular expressions as domains */
    const val ENTITY = "entity-domain"
    const val INVALID_REGEX = "invalid-regex"
    const val INVALID_SELECTOR = "invalid-selector"
    /** Would match (almost) every request or page: an empty or very short pattern without domains */
    const val TOO_GENERIC = "too-generic"
    const val INVALID = "invalid"
}
