package com.phlox.tvwebbrowser.adblock.parser

import com.phlox.tvwebbrowser.adblock.ResourceType
import java.net.IDN
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Reads filter lists of the Adblock Plus syntax (EasyList and the lists like it), one line at a time.
 *
 * Supported: request filters (`||`, `|`, `^`, `*`, `/regex/`, exceptions `@@`, the options `third-party`,
 * `domain`, the types and their negation, `important`, `match-case`, `document`, `elemhide`, `generichide`,
 * and the aliases of uBlock Origin for them), element hiding (`##`, `#@#`) and the lines of hosts files.
 * Everything else is skipped and counted in [stats]: a filter with an option it does not know is skipped
 * whole, because without the option it would block more than its list meant.
 */
class FilterParser(
    /** Told about each skipped filter: its line and the reason (for tests and debugging) */
    private val onSkip: ((line: String, reason: String) -> Unit)? = null,
) {
    val stats = ParseStats()

    /** Parses all [lines], giving each filter to [onFilter]; returns [stats] */
    fun parseList(lines: Sequence<String>, onFilter: (Filter) -> Unit): ParseStats {
        for (line in lines) parse(line)?.let(onFilter)
        return stats
    }

    /** The filter of the [line]; null for comments and skipped filters (counted in [stats]) */
    fun parse(line: String): Filter? {
        stats.lines++
        val text = line.trim()
        if (text.isEmpty() || text[0] == '!' || (text[0] == '[' && text.last() == ']')) {
            stats.comments++
            if (text.startsWith('!')) readHeader(text)
            return null
        }
        return try {
            val filter = parseFilter(text)
            if (filter == null) {
                stats.comments++
            } else if (filter is NetworkFilter) {
                stats.network++
            } else {
                stats.cosmetic++
            }
            filter
        } catch (e: SkipException) {
            stats.skip(e.reason)
            onSkip?.invoke(text, e.reason)
            null
        }
    }

    private fun parseFilter(text: String): Filter? {
        val marker = findCosmeticMarker(text)
        if (marker != null) return parseCosmetic(text, marker)
        //a comment of uBlock Origin lists ("# …"); "##…" lines are generic element hiding
        if (text[0] == '#') return null
        if (text.contains("$$") || text.contains("\$@$")) throw SkipException(SkipReason.HTML)
        parseHostsLine(text)?.let { return it }
        return parseNetwork(text)
    }

    // --- Element hiding ---

    private class Marker(val start: Int, val end: Int, val exception: Boolean, val skipReason: String?)

    private fun findCosmeticMarker(text: String): Marker? {
        var i = text.indexOf('#')
        while (i >= 0) {
            for ((token, exception, reason) in COSMETIC_MARKERS) {
                if (text.startsWith(token, i)) return Marker(i, i + token.length, exception, reason)
            }
            i = text.indexOf('#', i + 1)
        }
        return null
    }

    private fun parseCosmetic(text: String, marker: Marker): Filter {
        marker.skipReason?.let { throw SkipException(it) }
        val selector = text.substring(marker.end).trim()
        when {
            selector.isEmpty() -> throw SkipException(SkipReason.INVALID_SELECTOR)
            selector.startsWith("+js(") -> throw SkipException(SkipReason.SCRIPTLET)
            selector.startsWith("^") -> throw SkipException(SkipReason.HTML)
        }
        val lower = selector.lowercase(Locale.ROOT)
        //"selector {style}" of Adblock Plus, ":style(…)" of uBlock Origin
        if (CSS_INJECTIONS.any { lower.contains(it) } || (selector.endsWith('}') && selector.contains('{'))) {
            throw SkipException(SkipReason.CSS_INJECTION)
        }
        if (PROCEDURAL_SELECTORS.any { lower.contains(it) }) throw SkipException(SkipReason.PROCEDURAL)
        //a selector goes into a stylesheet: nothing may close its rule, start a comment or an at-rule, or
        //escape what follows it
        if (selector.contains('{') || selector.contains('}') || selector.contains("/*") || selector.startsWith('@') ||
            selector.endsWith('\\')) {
            throw SkipException(SkipReason.INVALID_SELECTOR)
        }
        val domains = parseDomains(text.substring(0, marker.start), ',')
        return CosmeticFilter(selector, marker.exception, domains, text)
    }

    // --- Requests ---

    private fun parseNetwork(line: String): NetworkFilter {
        val isException = line.startsWith("@@")
        val text = if (isException) line.substring(2) else line
        if (text.isEmpty()) throw SkipException(SkipReason.INVALID)

        //the options start at the last '$'; a regular expression may have its own '$' before its closing slash
        var patternText = text
        var optionsText: String? = null
        val dollar = text.lastIndexOf('$')
        val isRegex: Boolean
        if (text[0] == '/' && dollar > 1 && text[dollar - 1] == '/') {
            isRegex = true
            patternText = text.substring(0, dollar)
            optionsText = text.substring(dollar + 1)
        } else if (text[0] == '/' && text.length > 2 && text.last() == '/') {
            isRegex = true
        } else {
            isRegex = false
            if (dollar >= 0) {
                patternText = text.substring(0, dollar)
                optionsText = text.substring(dollar + 1)
            }
        }

        val options = Options(isException)
        if (!optionsText.isNullOrEmpty()) options.parse(optionsText)

        var pattern: String
        var anchorHost = false
        var anchorStart = false
        var anchorEnd = false
        var isHostOnly = false
        if (isRegex) {
            pattern = patternText.substring(1, patternText.length - 1)
            if (pattern.isEmpty()) throw SkipException(SkipReason.INVALID_REGEX)
            try {
                Pattern.compile(pattern)
            } catch (e: PatternSyntaxException) {
                throw SkipException(SkipReason.INVALID_REGEX)
            }
        } else {
            pattern = patternText
            if (pattern.startsWith("||")) {
                anchorHost = true
                pattern = pattern.substring(2)
            } else if (pattern.startsWith("|")) {
                anchorStart = true
                pattern = pattern.substring(1)
            }
            if (pattern.endsWith("|")) {
                anchorEnd = true
                pattern = pattern.substring(0, pattern.length - 1)
            }
            if (anchorHost) pattern = hostToAscii(pattern)
            pattern = collapseStars(pattern)
            //a '*' at an end makes the anchor of that end meaningless
            if (pattern.startsWith('*')) {
                pattern = pattern.trimStart('*')
                anchorHost = false
                anchorStart = false
            }
            if (pattern.endsWith('*')) {
                pattern = pattern.trimEnd('*')
                anchorEnd = false
            }
            if (!options.matchCase) pattern = pattern.lowercase(Locale.ROOT)
            if (pattern.any { it.isWhitespace() }) throw SkipException(SkipReason.INVALID)
            if (pattern.count { it != '*' && it != '^' } < 2 && !options.isNarrow()) {
                throw SkipException(SkipReason.TOO_GENERIC)
            }
            //without the separator the pattern also matches longer hosts (example.community)
            if (anchorHost && !anchorEnd && pattern.endsWith('^')) {
                val host = pattern.substring(0, pattern.length - 1)
                if (host.contains('.') && host.all { isHostChar(it) }) {
                    isHostOnly = true
                    pattern = host
                }
            }
        }
        val types = options.types()
        if (types == 0 && !options.elemHide && !options.genericHide) throw SkipException(SkipReason.INVALID)
        return NetworkFilter(
            pattern = pattern, isException = isException, anchorHost = anchorHost, anchorStart = anchorStart,
            anchorEnd = anchorEnd, isRegex = isRegex, isHostOnly = isHostOnly, types = types,
            thirdParty = options.thirdParty, important = options.important, matchCase = options.matchCase,
            domains = options.domains, elemHide = options.elemHide, genericHide = options.genericHide, text = line,
        )
    }

    private inner class Options(private val isException: Boolean) {
        var includedTypes = 0
        var excludedTypes = 0
        var thirdParty: Boolean? = null
        var important = false
        var matchCase = false
        var domains: DomainList? = null
        var elemHide = false
        var genericHide = false

        fun parse(text: String) {
            for (option in text.split(',')) {
                val trimmed = option.trim()
                if (trimmed.isEmpty()) continue
                val negated = trimmed.startsWith('~')
                val nameAndValue = if (negated) trimmed.substring(1) else trimmed
                val eq = nameAndValue.indexOf('=')
                val name = (if (eq >= 0) nameAndValue.substring(0, eq) else nameAndValue).trim().lowercase(Locale.ROOT)
                val value = if (eq >= 0) nameAndValue.substring(eq + 1).trim() else null
                parseOption(name, value, negated)
            }
        }

        private fun parseOption(name: String, value: String?, negated: Boolean) {
            val type = ResourceType.byOption[name]
            fun unsupported() = SkipException("option:" + (if (negated) "~" else "") + name)
            when {
                type != null -> {
                    if (value != null) throw unsupported()
                    //"~document" changes nothing: filters apply to pages only with "document"
                    if (negated) excludedTypes = excludedTypes or type else includedTypes = includedTypes or type
                }
                name == "third-party" || name == "3p" -> thirdParty = !negated
                name == "first-party" || name == "1p" -> thirdParty = negated
                name == "domain" || name == "from" -> {
                    if (negated || value.isNullOrEmpty()) throw unsupported()
                    domains = parseDomains(value, '|') ?: throw SkipException(SkipReason.INVALID)
                }
                name == "important" && !negated && value == null -> important = true
                name == "match-case" && !negated && value == null -> matchCase = true
                (name == "elemhide" || name == "ehide") && isException && !negated && value == null -> elemHide = true
                (name == "generichide" || name == "ghide") && isException && !negated && value == null -> genericHide = true
                else -> throw unsupported()
            }
        }

        /**
         * Whether the options alone limit the filter enough to keep it without a pattern: its domains, or a few
         * types of requests (`*$ping,third-party`), never pages
         */
        fun isNarrow(): Boolean {
            if (domains != null) return true
            val types = types()
            return types != 0 && types and ResourceType.DOCUMENT == 0 && Integer.bitCount(types) <= 3
        }

        fun types(): Int {
            val hasPageOptions = elemHide || genericHide
            return when {
                includedTypes != 0 -> includedTypes and excludedTypes.inv()
                excludedTypes != 0 -> ResourceType.DEFAULT and excludedTypes.inv()
                //an exception for element hiding only does not let requests through
                hasPageOptions -> 0
                else -> ResourceType.DEFAULT
            }
        }
    }

    // --- Hosts files ---

    private fun parseHostsLine(text: String): NetworkFilter? {
        val space = text.indexOfFirst { it == ' ' || it == '\t' }
        if (space < 0 || text.substring(0, space) !in HOSTS_ADDRESSES) return null
        val host = text.substring(space).trim().substringBefore('#').trim().split(' ', '\t')[0].lowercase(Locale.ROOT)
        if (host in LOCAL_HOSTS || !host.contains('.')) return null
        val ascii = hostToAscii(host)
        if (!ascii.all { isHostChar(it) }) throw SkipException(SkipReason.INVALID)
        return NetworkFilter(
            pattern = ascii, isException = false, anchorHost = true, anchorStart = false, anchorEnd = false,
            isRegex = false, isHostOnly = true, types = ResourceType.DEFAULT, thirdParty = null, important = false,
            matchCase = false, domains = null, elemHide = false, genericHide = false, text = text,
        )
    }

    // --- Domains ---

    /** The domains of [text] separated by [separator]; null when it names none */
    private fun parseDomains(text: String, separator: Char): DomainList? {
        if (text.isBlank()) return null
        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
        var droppedIncluded = false
        for (entry in text.split(separator)) {
            var domain = entry.trim()
            if (domain.isEmpty()) continue
            val negated = domain.startsWith('~')
            if (negated) domain = domain.substring(1).trim()
            domain = domain.lowercase(Locale.ROOT).trimEnd('.')
            //"*" of uBlock Origin: all sites
            if (domain == "*" && !negated) continue
            //entities (example.*) and regular expressions are not supported: an included one is left out (the
            //filter keeps its other domains), an excluded one would make the filter apply there
            if (domain.endsWith(".*") || domain.startsWith('/')) {
                if (negated) throw SkipException(SkipReason.ENTITY)
                droppedIncluded = true
                continue
            }
            domain = hostToAscii(domain)
            if (domain.isEmpty() || !domain.all { isDomainChar(it) }) throw SkipException(SkipReason.INVALID)
            (if (negated) exclude else include) += domain
        }
        //without its included domains the filter would apply everywhere
        if (droppedIncluded && include.isEmpty()) throw SkipException(SkipReason.ENTITY)
        if (include.isEmpty() && exclude.isEmpty()) return null
        return DomainList(include, exclude)
    }

    /** The [text] with its leading host converted to punycode when it is not ASCII */
    private fun hostToAscii(text: String): String {
        if (text.all { it.code < 0x80 }) return text
        val end = text.indexOfFirst { it in HOST_END_CHARS }.let { if (it < 0) text.length else it }
        val host = try {
            IDN.toASCII(text.substring(0, end), IDN.ALLOW_UNASSIGNED)
        } catch (e: IllegalArgumentException) {
            throw SkipException(SkipReason.INVALID)
        }
        return host.lowercase(Locale.ROOT) + text.substring(end)
    }

    private fun collapseStars(pattern: String): String {
        if (!pattern.contains("**")) return pattern
        val sb = StringBuilder(pattern.length)
        for (c in pattern) if (c != '*' || sb.isEmpty() || sb.last() != '*') sb.append(c)
        return sb.toString()
    }

    private fun isHostChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'

    /** Domains of options may also be IPv6 addresses ([::1]) */
    private fun isDomainChar(c: Char) = isHostChar(c) || c == ':' || c == '[' || c == ']'

    // --- Header ---

    private fun readHeader(text: String) {
        val colon = text.indexOf(':')
        if (colon < 0) return
        val key = text.substring(1, colon).trim().lowercase(Locale.ROOT)
        val value = text.substring(colon + 1).trim()
        when (key) {
            "title" -> if (stats.title == null) stats.title = value
            "version" -> if (stats.version == null) stats.version = value
            "expires" -> if (stats.expiresHours == null) stats.expiresHours = parseExpires(value)
        }
    }

    private fun parseExpires(value: String): Int? {
        val match = EXPIRES.find(value.lowercase(Locale.ROOT)) ?: return null
        val number = match.groupValues[1].toIntOrNull() ?: return null
        return if (match.groupValues[2].startsWith("h")) number else number * 24
    }

    /** Skips the filter being parsed; no stack trace, it is not an error */
    private class SkipException(val reason: String) : Exception(reason, null, false, false)

    private companion object {
        /** Element hiding markers, longest first: the token, whether it is an exception, why it is skipped */
        val COSMETIC_MARKERS = listOf(
            Triple("#@\$?#", true, SkipReason.CSS_INJECTION),
            Triple("#\$?#", false, SkipReason.CSS_INJECTION),
            Triple("#@\$#", true, SkipReason.CSS_INJECTION),
            Triple("#@?#", true, SkipReason.PROCEDURAL),
            Triple("#@%#", true, SkipReason.SCRIPTLET),
            Triple("#@#", true, null),
            Triple("#?#", false, SkipReason.PROCEDURAL),
            Triple("#\$#", false, SkipReason.SCRIPTLET),
            Triple("#%#", false, SkipReason.SCRIPTLET),
            Triple("##", false, null),
        )

        /** Pseudo-classes only ad blockers understand (`:has()` too: old WebViews do not know it) */
        val PROCEDURAL_SELECTORS = listOf(
            ":has(", ":has-text(", ":-abp-", ":xpath(", ":matches-css", ":matches-attr(", ":matches-path(",
            ":matches-prop", ":matches-media(", ":min-text-length(", ":upward(", ":watch-attr(", ":remove(",
            ":remove-attr(", ":remove-class(", ":others(", ":if(", ":if-not(", ":nth-ancestor(", ":contains(",
            ":-ext-", ":shadow(",
        )
        val CSS_INJECTIONS = listOf(":style(")

        val HOSTS_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "::0")
        val LOCAL_HOSTS = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost",
            "ip6-loopback")
        const val HOST_END_CHARS = "/^*|?:$"
        val EXPIRES = Regex("""(\d+)\s*(d|day|days|h|hour|hours)\b""")
    }
}
