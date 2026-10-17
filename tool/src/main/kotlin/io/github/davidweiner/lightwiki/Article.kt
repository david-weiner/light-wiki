package io.github.davidweiner.lightwiki

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A run of text with one style. [link] is the title of the article it points to. */
@Serializable
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val link: String? = null,
)

@Serializable
sealed interface Block {
    @Serializable
    @SerialName("h")
    data class Heading(val text: String, val level: Int) : Block

    @Serializable
    @SerialName("p")
    data class Paragraph(val spans: List<Span>) : Block

    @Serializable
    @SerialName("li")
    data class ListItem(val spans: List<Span>, val depth: Int) : Block

    @Serializable
    @SerialName("img")
    data class Image(
        val url: String,
        val largeUrl: String?,
        val caption: String?,
        val alt: String?,
        /** The image's file page, e.g. "File:Moon.jpg". */
        val file: String? = null,
        /** Short license name, e.g. "CC BY-SA 4.0" or "Public domain". Null until checked. */
        val license: String? = null,
        val author: String? = null,
    ) : Block {
        /** Only images whose free license has been confirmed are shown. */
        val canShow: Boolean get() = license != null
    }
}

/** What Wikimedia says about one image file. */
data class ImageLicense(val license: String?, val author: String?, val nonFree: Boolean)

/**
 * Attaches license details to each image and drops images that are not freely licensed
 * (Wikipedia's fair-use images can't be reused here) or whose license couldn't be found.
 */
fun withImageLicenses(blocks: List<Block>, licenses: Map<String, ImageLicense>): List<Block> =
    blocks.mapNotNull { block ->
        if (block !is Block.Image) return@mapNotNull block
        val info = block.file?.let(licenses::get) ?: return@mapNotNull null
        if (info.nonFree || info.license.isNullOrBlank()) return@mapNotNull null
        block.copy(license = info.license, author = info.author)
    }

data class Section(val title: String, val level: Int, val blockIndex: Int)

@Serializable
data class Article(val title: String, val blocks: List<Block>) {
    val sections: List<Section>
        get() = blocks.mapIndexedNotNull { i, block ->
            (block as? Block.Heading)?.let { Section(it.text, it.level, i) }
        }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): Article = json.decodeFromString(serializer(), text)
    }
}
