package io.github.yueeng.hacg

import androidx.preference.PreferenceManager
import java.util.Date

object Favorites {
    private const val KEY = "favorites.json"

    private val preference
        get() = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance)

    private var file: FavoritesFile
        get() = parse(preference.getString(KEY, null)) ?: FavoritesFile()
        set(value) = preference.edit().putString(KEY, gson.toJson(value.copy(updatedAt = System.currentTimeMillis()))).apply()

    fun all(): List<Article> = file.items.orEmpty().map { it.toArticle() }

    fun contains(article: Article): Boolean {
        val key = article.favoriteKey() ?: return false
        return file.items.orEmpty().any { it.favoriteKey() == key }
    }

    fun toggle(article: Article): Boolean {
        val key = article.favoriteKey() ?: return false
        val items = file.items.orEmpty().toMutableList()
        val index = items.indexOfFirst { it.favoriteKey() == key }
        val added = index < 0
        if (added) items.add(0, FavoriteArticle(article)) else items.removeAt(index)
        file = FavoritesFile(items = items)
        return added
    }

    fun exportJson(): String = gson.toJson(file)

    fun importJson(json: String): Int {
        val incoming = (parse(json) ?: error("Invalid favorites file")).items.orEmpty()
        val items = file.items.orEmpty().toMutableList()
        var added = 0
        incoming.asReversed().forEach { item ->
            val key = item.favoriteKey()
            if (key != null && items.none { it.favoriteKey() == key }) {
                items.add(0, item)
                added += 1
            }
        }
        file = FavoritesFile(items = items)
        return added
    }

    private fun parse(json: String?): FavoritesFile? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, FavoritesFile::class.java)
        } catch (_: Exception) {
            try {
                FavoritesFile(items = gson.fromJson(json, Array<FavoriteArticle>::class.java).toList())
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun Article.favoriteKey(): String? = id.takeIf { it > 0 }?.let { "id:$it" }
        ?: link?.takeIf { it.isNotBlank() }?.let { "url:$it" }

    private fun FavoriteArticle.favoriteKey(): String? = id.takeIf { it > 0 }?.let { "id:$it" }
        ?: link?.takeIf { it.isNotBlank() }?.let { "url:$it" }
}

data class FavoritesFile(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val items: List<FavoriteArticle>? = emptyList()
)

data class FavoriteArticle(
    val id: Int,
    val title: String?,
    val link: String?,
    val image: String?,
    val content: String?,
    val time: Long?,
    val comments: Int,
    val author: Tag?,
    val category: Tag?,
    val tags: List<Tag>?
) {
    constructor(article: Article) : this(
        article.id,
        article.title,
        article.link,
        article.image,
        article.content,
        article.time?.time,
        article.comments,
        article.author,
        article.category,
        article.tags
    )

    fun toArticle(): Article = Article(
        id,
        title ?: "",
        link,
        image,
        content,
        time?.let { Date(it) },
        comments,
        author,
        category,
        tags.orEmpty()
    )
}
