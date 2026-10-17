package io.github.davidweiner.lightwiki

import java.net.URLDecoder

/**
 * Converts the HTML produced by MediaWiki's `action=parse` into a short list of [Block]s.
 *
 * Only what reads well on a small screen is kept: headings, paragraphs, list items (with
 * bold, italics and links to other articles) and figure images. References, tables,
 * navigation boxes, edit links and styling are dropped. The result is far smaller than
 * the HTML, which matters because it is stored on the device.
 */
object ArticleHtml {

    fun parse(title: String, html: String): Article = Builder().run(html).let { Article(title, it) }

    private val VOID_ELEMENTS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
    )

    // Whole subtrees that never contain anything worth reading on the phone.
    private val SKIPPED_ELEMENTS = setOf("style", "script", "math", "table", "noscript", "audio", "video", "map")
    private val SKIPPED_CLASSES = setOf(
        "reference", "reflist", "references", "mw-references-wrap", "navbox", "navbox-styles", "hatnote",
        "noprint", "metadata", "mw-editsection", "mw-empty-elt", "gallery", "sistersitebox", "side-box",
        "shortdescription", "mw-cite-backlink", "toc", "portalbox", "mw-kartographer-maplink", "cite-bracket",
    )

    private val SKIPPED_SECTIONS = setOf(
        "see also", "references", "external links", "notes", "further reading", "sources",
        "bibliography", "citations", "footnotes", "notes and references", "works cited", "gallery",
        "explanatory notes",
    )

    private val NON_ARTICLE_NAMESPACES = setOf(
        "file", "image", "category", "help", "wikipedia", "template", "special", "portal", "talk",
        "user", "module", "mediawiki", "draft", "user talk", "template talk", "wikipedia talk",
    )

    private val INLINE_CONTAINERS = setOf("p", "li", "dd", "dt")

    private class Tag(val name: String, val classes: Set<String>)

    private class Builder {
        private val blocks = mutableListOf<Block>()
        private val stack = ArrayDeque<Tag>()

        // Depth in [stack] at which a skipped subtree started, or -1.
        private var skipDepth = -1
        private var skippingSection = false
        private var skippedSectionLevel = 0

        private var inline: InlineBuilder? = null
        private var inlineDepth = -1
        private var listDepth = 0

        private var heading: StringBuilder? = null
        private var headingLevel = 0

        private var figure: FigureBuilder? = null
        private var leadImageTaken = false

        // The file page ("File:Moon.jpg") the most recent image link points to. Images are
        // wrapped in such a link, and the name is needed to look up the image's license.
        private var fileLink: String? = null

        private var bold = 0
        private var italic = 0
        private val links = ArrayDeque<String?>()

        fun run(html: String): List<Block> {
            HtmlTokenizer(html).forEach(::onStart, ::onEnd, ::onText)
            flushInline()
            return withoutEmptySections(blocks)
        }

        private fun onStart(name: String, attrs: Map<String, String>) {
            val tag = Tag(name, attrs["class"]?.split(' ')?.filter(String::isNotEmpty)?.toSet().orEmpty())
            val void = name in VOID_ELEMENTS
            if (!void) stack.addLast(tag)
            if (name == "a") fileLink = fileLink(attrs)
            if (skipDepth >= 0) {
                // Still pull the main picture out of the infobox before it is thrown away.
                if (name == "img" && !leadImageTaken && blocks.isEmpty() && stack.any { "infobox" in it.classes }) {
                    imageFrom(attrs, fileLink)?.let { blocks += it; leadImageTaken = true }
                }
                return
            }

            val level = headingLevel(name)
            if (level != null) {
                flushInline()
                heading = StringBuilder()
                headingLevel = level
                return
            }
            if (skippingSection) return

            if (name in SKIPPED_ELEMENTS || tag.classes.any { it in SKIPPED_CLASSES } ||
                (name == "sup" && "reference" in tag.classes) ||
                attrs["style"]?.replace(" ", "")?.contains("display:none") == true
            ) {
                if (!void) skipDepth = stack.size
                return
            }

            when (name) {
                "ul", "ol", "dl" -> {
                    flushInline()
                    listDepth++
                }
                "figure" -> {
                    flushInline()
                    figure = FigureBuilder()
                }
                "img" -> {
                    val fig = figure
                    when {
                        fig != null && fig.image == null -> fig.image = imageFrom(attrs, fileLink)
                        // Formulas fall back to an image whose alt text is the formula itself.
                        attrs["class"]?.contains("mwe-math-fallback-image") == true ->
                            inline?.append(attrs["alt"].orEmpty(), bold > 0, italic > 0, links.lastOrNull())
                    }
                }
                "br" -> inline?.append(" ", false, false, null)
                "b", "strong" -> bold++
                "i", "em", "cite" -> italic++
                "a" -> links.addLast(articleLink(attrs))
            }

            if (name in INLINE_CONTAINERS && figure == null) {
                flushInline()
                inline = InlineBuilder(if (name == "p") 0 else listDepth.coerceAtLeast(1))
                inlineDepth = stack.size
            }
        }

        private fun onEnd(name: String) {
            // Close back to the matching open tag, tolerating sloppy markup.
            if (stack.none { it.name == name }) return
            while (stack.isNotEmpty()) {
                val closed = stack.removeLast()
                val depthAfter = stack.size
                closeTag(closed, depthAfter + 1)
                if (closed.name == name) break
            }
        }

        private fun closeTag(tag: Tag, depth: Int) {
            if (skipDepth >= 0) {
                if (depth == skipDepth) skipDepth = -1
                return
            }
            val level = headingLevel(tag.name)
            if (level != null) {
                val text = heading?.toString()?.collapseWhitespace().orEmpty()
                heading = null
                if (level <= skippedSectionLevel || !skippingSection) {
                    skippingSection = text.lowercase() in SKIPPED_SECTIONS
                    skippedSectionLevel = if (skippingSection) level else 0
                }
                if (!skippingSection && text.isNotEmpty()) blocks += Block.Heading(text, level)
                return
            }
            if (skippingSection) return

            when (tag.name) {
                "ul", "ol", "dl" -> {
                    flushInline()
                    listDepth = (listDepth - 1).coerceAtLeast(0)
                }
                "figure" -> {
                    figure?.let { fig ->
                        fig.image?.let { blocks += it.copy(caption = fig.caption.toString().collapseWhitespace().ifEmpty { null }) }
                    }
                    figure = null
                }
                "b", "strong" -> bold = (bold - 1).coerceAtLeast(0)
                "i", "em", "cite" -> italic = (italic - 1).coerceAtLeast(0)
                "a" -> links.removeLastOrNull()
            }
            if (depth == inlineDepth) flushInline()
        }

        private fun onText(text: String) {
            if (skipDepth >= 0) return
            heading?.let { it.append(text); return }
            if (skippingSection) return
            figure?.let { fig ->
                if (stack.any { it.name == "figcaption" }) fig.caption.append(text)
                return
            }
            inline?.append(text, bold > 0, italic > 0, links.lastOrNull())
        }

        private fun flushInline() {
            val current = inline ?: return
            inline = null
            inlineDepth = -1
            val spans = current.build()
            if (spans.isEmpty()) return
            blocks += if (current.listDepth == 0) Block.Paragraph(spans) else Block.ListItem(spans, current.listDepth)
        }

        private fun headingLevel(name: String): Int? =
            if (name.length == 2 && name[0] == 'h' && name[1] in '2'..'6') name[1] - '0' else null
    }

    /** Drops headings with nothing under them, e.g. sections that only held a table. */
    fun withoutEmptySections(blocks: List<Block>): List<Block> {
        val keep = BooleanArray(blocks.size) { true }
        for (i in blocks.indices.reversed()) {
            val heading = blocks[i] as? Block.Heading ?: continue
            var j = i + 1
            var hasContent = false
            while (j < blocks.size) {
                val next = blocks[j]
                if (next is Block.Heading && next.level <= heading.level) break
                if (keep[j] && next !is Block.Heading) {
                    hasContent = true
                    break
                }
                j++
            }
            keep[i] = hasContent
        }
        return blocks.filterIndexed { i, _ -> keep[i] }
    }

    private class FigureBuilder {
        var image: Block.Image? = null
        val caption = StringBuilder()
    }

    private class InlineBuilder(val listDepth: Int) {
        private val spans = mutableListOf<Span>()

        fun append(text: String, bold: Boolean, italic: Boolean, link: String?) {
            if (text.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic && last.link == link) {
                spans[spans.lastIndex] = last.copy(text = last.text + text)
            } else {
                spans += Span(text, bold, italic, link)
            }
        }

        fun build(): List<Span> {
            // Collapse whitespace across span boundaries, then trim the ends of the block.
            val collapsed = mutableListOf<Span>()
            var previousEndsWithSpace = true
            for (span in spans) {
                var text = span.text.replace(WHITESPACE, " ")
                if (previousEndsWithSpace) text = text.trimStart()
                if (text.isEmpty()) continue
                previousEndsWithSpace = text.endsWith(' ')
                collapsed += span.copy(text = text)
            }
            while (collapsed.isNotEmpty()) {
                val last = collapsed.last()
                val trimmed = last.text.trimEnd()
                if (trimmed.isEmpty()) collapsed.removeAt(collapsed.lastIndex)
                else {
                    collapsed[collapsed.lastIndex] = last.copy(text = trimmed)
                    break
                }
            }
            return collapsed
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun String.collapseWhitespace() = replace(WHITESPACE, " ").trim()

    /** Returns the article title an `<a>` points to, or null if it isn't another article. */
    private fun articleLink(attrs: Map<String, String>): String? {
        val href = attrs["href"] ?: return null
        val classes = attrs["class"].orEmpty()
        if (!href.startsWith("/wiki/") || "new" in classes.split(' ') || "external" in classes) return null
        val path = href.removePrefix("/wiki/").substringBefore('#')
        if (path.isEmpty()) return null
        val title = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8").replace('_', ' ')
        val namespace = title.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (namespace in NON_ARTICLE_NAMESPACES) return null
        return title
    }

    /** Returns "File:Name.jpg" for a link to an image's file page, otherwise null. */
    private fun fileLink(attrs: Map<String, String>): String? {
        val href = attrs["href"] ?: return null
        if (!href.startsWith("/wiki/File:")) return null
        return URLDecoder.decode(href.removePrefix("/wiki/").substringBefore('#').replace("+", "%2B"), "UTF-8")
            .replace('_', ' ')
    }

    private fun imageFrom(attrs: Map<String, String>, file: String?): Block.Image? {
        val width = attrs["width"]?.toIntOrNull() ?: 0
        if (width in 1 until 100) return null // icons, flags and the like
        val src = attrs["src"] ?: return null
        // srcset lists larger renditions, e.g. "//a/500px-x.jpg 1.5x, //a/660px-x.jpg 2x".
        val large = attrs["srcset"]?.split(',')?.lastOrNull()?.trim()?.substringBefore(' ')?.ifBlank { null }
        return Block.Image(
            url = absoluteUrl(src),
            largeUrl = large?.let(::absoluteUrl),
            caption = null,
            alt = attrs["alt"]?.collapseWhitespace()?.ifEmpty { null },
            file = file,
        )
    }

    private fun absoluteUrl(url: String): String {
        // Drop the analytics tags Wikipedia appends to image URLs.
        val clean = url.substringBefore('?')
        return if (clean.startsWith("//")) "https:$clean" else clean
    }
}

/** A small, forgiving HTML tokenizer: enough for MediaWiki output, not a general parser. */
internal class HtmlTokenizer(private val html: String) {

    fun forEach(
        onStart: (name: String, attrs: Map<String, String>) -> Unit,
        onEnd: (name: String) -> Unit,
        onText: (String) -> Unit,
    ) {
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                onText(decodeEntities(html.substring(i)))
                break
            }
            if (lt > i) onText(decodeEntities(html.substring(i, lt)))
            when {
                html.startsWith("<!--", lt) -> {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end < 0) n else end + 3
                }
                html.startsWith("</", lt) -> {
                    val gt = html.indexOf('>', lt)
                    if (gt < 0) break
                    onEnd(html.substring(lt + 2, gt).trim().lowercase())
                    i = gt + 1
                }
                lt + 1 < n && (html[lt + 1].isLetter()) -> {
                    i = readStartTag(lt, onStart)
                }
                html.startsWith("<!", lt) || html.startsWith("<?", lt) -> {
                    val gt = html.indexOf('>', lt)
                    i = if (gt < 0) n else gt + 1
                }
                else -> {
                    onText("<")
                    i = lt + 1
                }
            }
        }
    }

    private fun readStartTag(lt: Int, onStart: (String, Map<String, String>) -> Unit): Int {
        var i = lt + 1
        val n = html.length
        val nameStart = i
        while (i < n && (html[i].isLetterOrDigit() || html[i] == '-' || html[i] == ':')) i++
        val name = html.substring(nameStart, i).lowercase()
        val attrs = HashMap<String, String>()
        while (i < n) {
            while (i < n && html[i].isWhitespace()) i++
            if (i >= n) break
            if (html[i] == '>') { i++; break }
            if (html[i] == '/') { i++; continue }
            val keyStart = i
            while (i < n && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>' && html[i] != '/') i++
            val key = html.substring(keyStart, i).lowercase()
            while (i < n && html[i].isWhitespace()) i++
            var value = ""
            if (i < n && html[i] == '=') {
                i++
                while (i < n && html[i].isWhitespace()) i++
                if (i < n && (html[i] == '"' || html[i] == '\'')) {
                    val quote = html[i]
                    val end = html.indexOf(quote, i + 1).let { if (it < 0) n else it }
                    value = html.substring(i + 1, end)
                    i = (end + 1).coerceAtMost(n)
                } else {
                    val valueStart = i
                    while (i < n && !html[i].isWhitespace() && html[i] != '>') i++
                    value = html.substring(valueStart, i)
                }
            }
            if (key.isNotEmpty()) attrs[key] = decodeEntities(value)
        }
        onStart(name, attrs)
        return i
    }

    companion object {
        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "ndash" to "–", "mdash" to "—", "minus" to "−", "times" to "×", "deg" to "°", "hellip" to "…",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·", "thinsp" to " ",
            "shy" to "", "zwj" to "‍", "zwnj" to "‌", "prime" to "′", "Prime" to "″",
        )
        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

        fun decodeEntities(text: String): String {
            if ('&' !in text) return text
            return ENTITY.replace(text) { match ->
                val body = match.groupValues[1]
                when {
                    body.startsWith("#x") || body.startsWith("#X") ->
                        body.substring(2).toIntOrNull(16)?.let(::codePointString) ?: match.value
                    body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePointString) ?: match.value
                    else -> NAMED[body] ?: match.value
                }
            }
        }

        private fun codePointString(codePoint: Int): String? =
            if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null
    }
}
