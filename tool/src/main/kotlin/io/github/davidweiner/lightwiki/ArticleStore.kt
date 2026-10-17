package io.github.davidweiner.lightwiki

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every article that has been opened is kept on the device, so it can be reread without
 * a connection. Saved articles are kept until deleted; unsaved ones are trimmed to the
 * most recent [ArticleStore.RECENT_LIMIT].
 *
 * [content] is the article as [Article.toJson], not the original HTML, which can be
 * several megabytes (more than an Android database row can hold).
 */
@Entity(tableName = "articles")
data class StoredArticle(
    @PrimaryKey val title: String,
    val content: String,
    val saved: Boolean,
    val openedAt: Long,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
)

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles WHERE title = :title")
    suspend fun get(title: String): StoredArticle?

    @Query("SELECT title FROM articles WHERE saved = 1 ORDER BY title COLLATE NOCASE")
    fun savedTitles(): Flow<List<String>>

    @Query("SELECT title FROM articles WHERE saved = 0 ORDER BY openedAt DESC")
    fun recentTitles(): Flow<List<String>>

    @Query("SELECT saved FROM articles WHERE title = :title")
    fun isSaved(title: String): Flow<Boolean?>

    @Upsert
    suspend fun upsert(article: StoredArticle)

    @Query("UPDATE articles SET saved = :saved WHERE title = :title")
    suspend fun setSaved(title: String, saved: Boolean)

    @Query("UPDATE articles SET openedAt = :openedAt WHERE title = :title")
    suspend fun touch(title: String, openedAt: Long)

    @Query("UPDATE articles SET scrollIndex = :index, scrollOffset = :offset WHERE title = :title")
    suspend fun setScroll(title: String, index: Int, offset: Int)

    @Query("DELETE FROM articles WHERE title = :title")
    suspend fun delete(title: String)

    @Query(
        "DELETE FROM articles WHERE saved = 0 AND title NOT IN " +
            "(SELECT title FROM articles WHERE saved = 0 ORDER BY openedAt DESC LIMIT :keep)"
    )
    suspend fun trimRecent(keep: Int)
}

// The schema is exported to tool/src/main/assets/room-schemas (see tool/build.gradle.kts) so that
// future versions can declare @AutoMigration; Light's build server only copies files under src/main.
@Database(entities = [StoredArticle::class], version = 1, exportSchema = true)
abstract class WikiDatabase : RoomDatabase() {
    abstract fun articles(): ArticleDao
}

data class OpenedArticle(val article: Article, val scrollIndex: Int, val scrollOffset: Int)

class ArticleStore private constructor(private val dao: ArticleDao) {

    // Writes that must finish even if the screen that asked for them has already closed.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val savedTitles: Flow<List<String>> = dao.savedTitles()
    val recentTitles: Flow<List<String>> = dao.recentTitles()

    fun isSaved(title: String): Flow<Boolean?> = dao.isSaved(title)

    /** Returns the stored copy if there is one, otherwise downloads and stores it. */
    suspend fun open(title: String): OpenedArticle {
        val now = System.currentTimeMillis()
        dao.get(title)?.let { stored ->
            dao.touch(stored.title, now)
            val article = withContext(Dispatchers.Default) { Article.fromJson(stored.content) }
            return OpenedArticle(article, stored.scrollIndex, stored.scrollOffset)
        }
        val article = fitForStorage(WikiApi.fetchArticle(title))
        // A redirect may resolve to an article that is already stored (and maybe saved).
        val existing = dao.get(article.title)
        dao.upsert(
            StoredArticle(
                title = article.title,
                content = withContext(Dispatchers.Default) { article.toJson() },
                saved = existing?.saved ?: false,
                openedAt = now,
                scrollIndex = existing?.scrollIndex ?: 0,
                scrollOffset = existing?.scrollOffset ?: 0,
            )
        )
        dao.trimRecent(RECENT_LIMIT)
        return OpenedArticle(article, existing?.scrollIndex ?: 0, existing?.scrollOffset ?: 0)
    }

    suspend fun setSaved(title: String, saved: Boolean) {
        dao.setSaved(title, saved)
        if (!saved) dao.trimRecent(RECENT_LIMIT)
    }

    fun saveScroll(title: String, index: Int, offset: Int) {
        background.launch { dao.setScroll(title, index, offset) }
    }

    suspend fun delete(title: String) = dao.delete(title)

    /** Shortens the rare article whose stored form would be too big for one database row. */
    private suspend fun fitForStorage(article: Article): Article = withContext(Dispatchers.Default) {
        var blocks = article.blocks
        while (blocks.size > 1 && Article(article.title, blocks).toJson().encodeToByteArray().size > MAX_CONTENT_BYTES) {
            blocks = blocks.take(blocks.size * 9 / 10)
        }
        if (blocks.size == article.blocks.size) article
        else Article(article.title, blocks + Block.Paragraph(listOf(Span("This article was shortened to fit on your phone.", italic = true))))
    }

    companion object {
        const val RECENT_LIMIT = 10
        private const val MAX_CONTENT_BYTES = 1_500_000

        @Volatile
        private var instance: ArticleStore? = null

        fun get(context: SealedLightContext): ArticleStore =
            instance ?: synchronized(this) {
                instance ?: ArticleStore(
                    context.buildDatabase(WikiDatabase::class.java, "lightwiki.db").articles()
                ).also { instance = it }
            }
    }
}
