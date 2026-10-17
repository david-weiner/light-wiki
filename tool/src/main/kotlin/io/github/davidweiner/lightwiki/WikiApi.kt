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
        return withContext(Dispatchers.Default) { ArticleHtml.parse(parsed.title, parsed.text) }
    }

    suspend fun download(url: String): ByteArray = client.get(url).readRawBytes()

    @Serializable
    private data class QueryResponse(val query: Query? = null)

    @Serializable
    private data class Query(val pages: List<Page> = emptyList())

    @Serializable
    private data class Page(
        val title: String = "",
        val index: Int = 0,
        val description: String? = null,
    )

    @Serializable
    private data class ParseResponse(val parse: Parsed? = null, val error: ApiError? = null)

    @Serializable
    private data class Parsed(val title: String = "", val text: String = "")

    @Serializable
    private data class ApiError(val code: String = "")
}
