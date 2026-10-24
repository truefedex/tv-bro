package com.phlox.tvwebbrowser.adblock.parser

/** A filter of a list, as the parser reads it; the compiler turns them into the engine */
sealed interface Filter

/**
 * The domains a filter is limited to (`domain=a.com|~b.com`, `a.com,~b.com##…`): it applies on [include]
 * (all domains when empty) and their subdomains, except on [exclude] and their subdomains. Lowercase ASCII
 * (punycode).
 */
class DomainList(val include: List<String>, val exclude: List<String>) {
    override fun equals(other: Any?) = other is DomainList && include == other.include && exclude == other.exclude
    override fun hashCode() = include.hashCode() * 31 + exclude.hashCode()
    override fun toString() = (include + exclude.map { "~$it" }).joinToString("|")
}

/**
 * A filter of requests.
 *
 * [pattern] is what the url has to contain, without the anchors and the options: lowercase unless
 * [matchCase], `*` (any characters) and `^` (a separator or the end) inside, never at its ends for `*`.
 * It may be empty (any url, the filter is limited by its options). For a [isRegex] filter it is the regular
 * expression between the slashes, as written.
 */
class NetworkFilter(
    val pattern: String,
    /** `@@`: an exception, the requests it matches are not blocked */
    val isException: Boolean,
    /** `||`: the pattern starts at the beginning of the host or of one of its labels */
    val anchorHost: Boolean,
    /** `|` at the start: the pattern starts at the beginning of the url */
    val anchorStart: Boolean,
    /** `|` at the end: the pattern ends at the end of the url */
    val anchorEnd: Boolean,
    val isRegex: Boolean,
    /**
     * `||host^` without other pattern: the filter matches the requests to the host and its subdomains,
     * [pattern] is the host
     */
    val isHostOnly: Boolean,
    /** The [ResourceType]s it applies to; 0 for exceptions that only turn off element hiding */
    val types: Int,
    /** true: only requests to other sites (`third-party`), false: only to the same site, null: both */
    val thirdParty: Boolean?,
    val important: Boolean,
    val matchCase: Boolean,
    /** `domain=`: the domains of the page (document) the filter applies on */
    val domains: DomainList?,
    /** `@@…$elemhide`: no element hiding on the pages the filter matches */
    val elemHide: Boolean,
    /** `@@…$generichide`: no generic element hiding on the pages the filter matches */
    val genericHide: Boolean,
    /** The line of the list, for debugging */
    val text: String,
) : Filter {
    override fun toString() = text
}

/**
 * A filter hiding elements: `domains##selector`, or an exception `domains#@#selector`.
 * A filter without [DomainList.include] domains is generic (applies on all sites but the excluded ones).
 */
class CosmeticFilter(
    val selector: String,
    val isException: Boolean,
    val domains: DomainList?,
    val text: String,
) : Filter {
    val isGeneric: Boolean get() = domains == null || domains.include.isEmpty()

    override fun toString() = text
}
