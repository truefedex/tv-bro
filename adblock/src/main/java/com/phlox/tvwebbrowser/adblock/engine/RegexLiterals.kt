package com.phlox.tvwebbrowser.adblock.engine

/** Literals of regular expressions, checked in urls before the expressions themselves */
internal object RegexLiterals {
    private const val MIN_LITERAL_LENGTH = 3

    /**
     * The longest run of characters every match of the regular expression [regex] contains (at least
     * [MIN_LITERAL_LENGTH] of them), or null. Conservative: nothing inside groups or classes, nothing with a
     * quantifier, nothing at all when there is an alternation outside groups.
     */
    fun required(regex: String): String? {
        var best = ""
        val run = StringBuilder()
        fun endRun() {
            if (run.length > best.length) best = run.toString()
            run.setLength(0)
        }
        var depth = 0
        var i = 0
        while (i < regex.length) {
            val c = regex[i]
            when {
                c == '\\' && i + 1 < regex.length -> {
                    val next = regex[i + 1]
                    //\d, \w, \b… are classes or assertions, an escaped punctuation is itself
                    if (depth == 0 && !next.isLetterOrDigit() && next.code < 0x80) run.append(next) else endRun()
                    i += 2
                    continue
                }
                c == '[' -> {
                    endRun()
                    //skip the class (a ']' right after '[' or '[^' belongs to it)
                    var j = i + 1
                    if (j < regex.length && regex[j] == '^') j++
                    if (j < regex.length && regex[j] == ']') j++
                    while (j < regex.length && regex[j] != ']') {
                        if (regex[j] == '\\') j++
                        j++
                    }
                    i = j + 1
                    continue
                }
                c == '(' -> {
                    endRun()
                    depth++
                }
                c == ')' -> {
                    endRun()
                    depth--
                }
                c == '|' -> {
                    if (depth == 0) return null
                    endRun()
                }
                c == '?' || c == '*' || c == '+' || c == '{' -> {
                    //the quantified character may be missing or repeated
                    if (run.isNotEmpty()) run.setLength(run.length - 1)
                    endRun()
                    if (c == '{') {
                        val close = regex.indexOf('}', i)
                        if (close > 0) i = close
                    }
                }
                c == '.' || c == '^' || c == '$' -> endRun()
                depth == 0 && c.code < 0x80 -> run.append(c)
                else -> endRun()
            }
            i++
        }
        endRun()
        return best.takeIf { it.length >= MIN_LITERAL_LENGTH }
    }
}
