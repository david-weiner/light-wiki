package io.github.davidweiner.lightwiki

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class SearchResult(val title: String, val description: String?)

class ArticleNotFoundException(title: String) : Exception("No article named \"$title\"")

/**
 * Talks to the MediaWiki Action API. Only article text and titles leave Wikipedia's
 * servers; nothing about the user is sent besides the query itself.
 */
object WikiApi {
    private const val ENDPOINT = "https://en.wikipedia.org/w/api.php"

    // Wikimedia asks API clients to identify themselves:
    // https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy
    private const val USER_AGENT = "LightWiki/1.0 (https://github.com/david-weiner/light-wiki)"

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
    }

    suspend fun search(query: String): List<SearchResult> {
        val response: QueryResponse = client.get(ENDPOINT) {
            parameter("action", "query")
            parameter("format", "json")
            parameter("formatversion", "2")
            parameter("generator", "search")
            parameter("gsrsearch", query)
            parameter("gsrlimit", "20")
            parameter("gsrnamespace", "0")
            parameter("prop", "description")
        }.body()
        return response.query?.pages.orEmpty()
            .sortedBy { it.index }
            .map { SearchResult(it.title, it.description?.takeIf(String::isNotBlank)) }
    }

    /** Downloads an article's HTML and boils it down to readable blocks. */
    suspend fun fetchArticle(title: String): Article {
        val response: ParseResponse = client.get(ENDPOINT) {
            parameter("action", "parse")
            parameter("format", "json")
            parameter("formatversion", "2")
            parameter("prop", "text")
            parameter("redirects", "1")
            parameter("disableeditsection", "1")
            parameter("disabletoc", "1")
            parameter("disablelimitreport", "1")
            parameter("page", title)
        }.body()
        val parsed = response.parse
        if (response.error?.code == "missingtitle" || parsed == null || parsed.text.isBlank()) {
            throw ArticleNotFoundException(title)
        }
        val article = withContext(Dispatchers.Default) { ArticleHtml.parse(parsed.title, parsed.text) }
        val files = article.blocks.mapNotNull { (it as? Block.Image)?.file }.distinct()
        // If the license lookup fails, images are left out rather than shown without credit.
        val licenses = runCatching { imageLicenses(files) }.getOrDefault(emptyMap())
        return article.copy(blocks = ArticleHtml.withoutEmptySections(withImageLicenses(article.blocks, licenses)))
    }

    /** Looks up license and author for image files, keyed by "File:Name.jpg". */
    private suspend fun imageLicenses(files: List<String>): Map<String, ImageLicense> {
        val result = mutableMapOf<String, ImageLicense>()
        for (batch in files.chunked(50)) { // the API's limit per request
            val response: QueryResponse = client.get(ENDPOINT) {
                parameter("action", "query")
                parameter("format", "json")
                parameter("formatversion", "2")
                parameter("prop", "imageinfo")
                parameter("iiprop", "extmetadata")
                parameter("iiextmetadatafilter", "LicenseShortName|Artist|Credit|NonFree")
                parameter("titles", batch.joinToString("|"))
            }.body()
            val query = response.query ?: continue
            // The API may rename titles (e.g. capitalization); map results back to what was asked.
            val requestedName = query.normalized.associate { it.to to it.from }
            for (page in query.pages) {
                val meta = page.imageinfo.firstOrNull()?.extmetadata ?: continue
                val info = ImageLicense(
                    license = meta["LicenseShortName"]?.text(),
                    author = (meta["Artist"] ?: meta["Credit"])?.text()?.take(160),
                    nonFree = meta["NonFree"]?.text()?.let { it == "true" || it == "1" } == true,
                )
                result[page.title] = info
                requestedName[page.title]?.let { result[it] = info }
            }
        }
        return result
    }

    private val TAG = Regex("<[^>]*>")
    private val SPACES = Regex("\\s+")

    /** extmetadata values are HTML snippets (often a link to the author's page); keep the text. */
    private fun MetaValue.text(): String? =
        (value as? JsonPrimitive)?.content
            ?.replace(TAG, " ")
            ?.let(HtmlTokenizer::decodeEntities)
            ?.replace(SPACES, " ")
            ?.trim()
            ?.ifEmpty { null }

    suspend fun download(url: String): ByteArray = client.get(url).readRawBytes()

    @Serializable
    private data class QueryResponse(val query: Query? = null)

    @Serializable
    private data class Query(
        val pages: List<Page> = emptyList(),
        val normalized: List<Normalized> = emptyList(),
    )

    @Serializable
    private data class Normalized(val from: String = "", val to: String = "")

    @Serializable
    private data class ImageInfo(val extmetadata: Map<String, MetaValue> = emptyMap())

    @Serializable
    private data class MetaValue(val value: JsonElement? = null)

    @Serializable
    private data class Page(
        val title: String = "",
        val index: Int = 0,
        val description: String? = null,
        val imageinfo: List<ImageInfo> = emptyList(),
    )

    @Serializable
    private data class ParseResponse(val parse: Parsed? = null, val error: ApiError? = null)

    @Serializable
    private data class Parsed(val title: String = "", val text: String = "")

    @Serializable
    private data class ApiError(val code: String = "")
}
