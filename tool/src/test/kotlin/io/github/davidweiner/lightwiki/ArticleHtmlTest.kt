package io.github.davidweiner.lightwiki

import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleHtmlTest {

    private val html = """
        <div class="mw-content-ltr mw-parser-output">
        <style>.x{color:red}</style>
        <div role="note" class="hatnote">For other uses, see <a href="/wiki/Thing_(disambiguation)">Thing</a>.</div>
        <table class="infobox"><tr><td class="infobox-image">
          <img src="//upload.wikimedia.org/thumb/lead.jpg/250px-lead.jpg?utm_source=x" width="250"
               srcset="//upload.wikimedia.org/thumb/lead.jpg/500px-lead.jpg 2x" alt="Lead">
          <img src="//upload.wikimedia.org/flag.png" width="20">
        </td></tr></table>
        <p>A <b>thing</b> is an <i>object</i> &amp; a <a href="/wiki/C%2B%2B" title="C++">C++</a> idea
        <a href="/wiki/Red_link_page" class="new">nowhere</a>.<sup class="reference"><a href="#cite_note-1">[1]</a></sup>
        See <a href="/wiki/Moon#Orbit">the Moon</a> and <a href="/wiki/File:X.jpg">a file</a>.</p>
        <div class="mw-heading mw-heading2"><h2 id="History">History</h2></div>
        <figure typeof="mw:File/Thumb"><a href="/wiki/File:Old.jpg"><img src="//upload.wikimedia.org/old.jpg" width="220"></a>
          <figcaption>An <a href="/wiki/Old">old</a> picture</figcaption></figure>
        <ul><li>First point</li><li>Second <ul><li>Nested point</li></ul></li></ul>
        <div class="mw-heading mw-heading2"><h2 id="References">References</h2></div>
        <p>Should not appear.</p>
        <div class="mw-heading mw-heading3"><h3>Inline</h3></div>
        <p>Nor this.</p>
        <div class="mw-heading mw-heading2"><h2>Legacy</h2></div>
        <p>Still&nbsp;here &#8212; really&#x21;</p>
        </div>
    """.trimIndent()

    @Test
    fun keepsReadableContentAndDropsTheRest() {
        val article = ArticleHtml.parse("Thing", html)

        assertEquals(
            listOf(
                Block.Image(
                    url = "https://upload.wikimedia.org/thumb/lead.jpg/250px-lead.jpg",
                    largeUrl = "https://upload.wikimedia.org/thumb/lead.jpg/500px-lead.jpg",
                    caption = null,
                    alt = "Lead",
                ),
                Block.Paragraph(
                    listOf(
                        Span("A "),
                        Span("thing", bold = true),
                        Span(" is an "),
                        Span("object", italic = true),
                        Span(" & a "),
                        Span("C++", link = "C++"),
                        Span(" idea nowhere. See "),
                        Span("the Moon", link = "Moon"),
                        Span(" and a file."),
                    )
                ),
                Block.Heading("History", 2),
                Block.Image("https://upload.wikimedia.org/old.jpg", null, "An old picture", null),
                Block.ListItem(listOf(Span("First point")), 1),
                Block.ListItem(listOf(Span("Second")), 1),
                Block.ListItem(listOf(Span("Nested point")), 2),
                Block.Heading("Legacy", 2),
                Block.Paragraph(listOf(Span("Still here — really!"))),
            ),
            article.blocks,
        )
        assertEquals(listOf("History", "Legacy"), article.sections.map { it.title })
    }

    @Test
    fun roundTripsThroughJson() {
        val article = ArticleHtml.parse("Thing", html)
        assertEquals(article, Article.fromJson(article.toJson()))
    }
}
