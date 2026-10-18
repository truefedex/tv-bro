package com.phlox.tvwebbrowser.data

/**
 * The HTML bookmarks file (NETSCAPE-Bookmark-file-1) that Chrome, Firefox and Edge import and export: a
 * folder is an H3 heading followed by a DL list of what is in it, a bookmark is an A link in a DT item.
 *
 * The Home page folder of TV Bro is marked with the [HOME_PAGE_ATTRIBUTE] of its H3, as Chrome and
 * Firefox mark their bookmarks bar with PERSONAL_TOOLBAR_FOLDER; other browsers import it as a plain folder.
 * Reading is lenient, as the files of browsers are not well-formed HTML: tags in any case, DT and P not
 * closed, attributes quoted or not, entities; descriptions (DD), separators and headings other than H3
 * are left out, and so are Firefox's queries (place: urls).
 */
object BookmarksHtml {
    sealed class Node {
        abstract val title: String
    }

    data class Folder(override val title: String, val children: List<Node>, val isHomePage: Boolean = false) : Node()

    data class Link(override val title: String, val url: String) : Node()

    const val HOME_PAGE_ATTRIBUTE = "TVBRO_HOME_PAGE"

    private const val INDENT = "    "

    /** The file of the [nodes] (the top level) */
    fun write(nodes: List<Node>): String {
        val out = StringBuilder()
        out.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        out.append("<!-- This is an automatically generated file.\n")
        out.append("     It will be read and overwritten.\n")
        out.append("     DO NOT EDIT! -->\n")
        out.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        out.append("<TITLE>Bookmarks</TITLE>\n")
        out.append("<H1>Bookmarks</H1>\n")
        writeList(out, nodes, 0)
        return out.toString()
    }

    private fun writeList(out: StringBuilder, nodes: List<Node>, depth: Int) {
        val indent = INDENT.repeat(depth)
        out.append(indent).append("<DL><p>\n")
        for (node in nodes) {
            out.append(indent).append(INDENT).append("<DT>")
            when (node) {
                is Folder -> {
                    out.append("<H3")
                    if (node.isHomePage) out.append(' ').append(HOME_PAGE_ATTRIBUTE).append("=\"true\"")
                    out.append('>').append(escape(node.title)).append("</H3>\n")
                    writeList(out, node.children, depth + 1)
                }
                is Link -> out.append("<A HREF=\"").append(escape(node.url)).append("\">")
                    .append(escape(node.title)).append("</A>\n")
            }
        }
        out.append(indent).append("</DL><p>\n")
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private class FolderBuilder(val isHomePage: Boolean) {
        var title = ""
        val children = ArrayList<Any>()

        fun build(): Folder = Folder(title, buildChildren(), isHomePage)

        fun buildChildren(): List<Node> = children.map { if (it is FolderBuilder) it.build() else it as Node }
    }

    private val tagRegex = Regex("""<!--.*?-->|<![^>]*>|<(/?)([A-Za-z][A-Za-z0-9]*)((?:[^>"']++|"[^"]*+"|'[^']*+')*+)>""",
        RegexOption.DOT_MATCHES_ALL)
    private val attributeRegex = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")
    private val entityRegex = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]+);""")
    private val whitespaceRegex = Regex("""\s+""")

    /** The top level of the file: its folders and bookmarks; empty if it has none (or is not such a file) */
    fun parse(html: String): List<Node> {
        val root = FolderBuilder(false)
        //the folders of the DL lists that are open, the innermost last
        val open = ArrayList<FolderBuilder>()
        var current = root
        //the folder of the last H3, waiting for its DL
        var pendingFolder: FolderBuilder? = null
        //the text of the H3 or A that is open
        var text: StringBuilder? = null
        //the folder of the H3 that is open
        var heading: FolderBuilder? = null
        var linkUrl: String? = null
        //an H3 or A ends at its closing tag, or at the next item or list when it is not closed
        fun finishText() {
            heading?.title = clean(text)
            val url = linkUrl
            if (url != null && url.isNotEmpty() && !url.startsWith("place:", ignoreCase = true)) {
                current.children += Link(clean(text).ifEmpty { url }, url)
            }
            heading = null
            linkUrl = null
            text = null
        }
        var lastEnd = 0
        for (match in tagRegex.findAll(html)) {
            text?.append(html, lastEnd, match.range.first)
            lastEnd = match.range.last + 1
            val name = match.groupValues[2].uppercase()
            if (name.isEmpty()) continue //a comment or the doctype
            val closing = match.groupValues[1].isNotEmpty()
            if (text != null && (name == "DT" || name == "DL" || (!closing && (name == "H3" || name == "A")))) {
                finishText()
            }
            when {
                name == "DL" && !closing -> {
                    open += current
                    current = pendingFolder ?: current
                    pendingFolder = null
                }
                name == "DL" -> {
                    if (open.isNotEmpty()) current = open.removeAt(open.lastIndex)
                    pendingFolder = null
                }
                name == "H3" && !closing -> {
                    val attributes = attributes(match.groupValues[3])
                    val folder = FolderBuilder(attributes[HOME_PAGE_ATTRIBUTE].equals("true", ignoreCase = true))
                    current.children += folder
                    pendingFolder = folder
                    heading = folder
                    text = StringBuilder()
                }
                name == "A" && !closing -> {
                    pendingFolder = null
                    linkUrl = attributes(match.groupValues[3])["HREF"]?.let { decode(it).trim() } ?: ""
                    text = StringBuilder()
                }
                (name == "H3" || name == "A") && text != null -> finishText()
            }
        }
        return root.buildChildren()
    }

    private fun attributes(text: String): Map<String, String> {
        val attributes = HashMap<String, String>()
        for (match in attributeRegex.findAll(text)) {
            val (name, doubleQuoted, singleQuoted, unquoted) = match.destructured
            attributes[name.uppercase()] = doubleQuoted.ifEmpty { singleQuoted.ifEmpty { unquoted } }
        }
        return attributes
    }

    private fun clean(text: CharSequence?): String =
        decode(text?.toString() ?: "").replace(whitespaceRegex, " ").trim()

    private fun decode(text: String): String = entityRegex.replace(text) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x") || entity.startsWith("#X") -> codePoint(entity.substring(2).toIntOrNull(16))
            entity.startsWith("#") -> codePoint(entity.substring(1).toIntOrNull())
            else -> namedEntities[entity.lowercase()]
        } ?: match.value
    }

    private fun codePoint(code: Int?): String? =
        if (code != null && Character.isValidCodePoint(code) && code != 0) String(Character.toChars(code)) else null

    private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ")
}
