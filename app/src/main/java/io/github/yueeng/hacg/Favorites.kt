package io.github.yueeng.hacg

import androidx.preference.PreferenceManager
import com.google.gson.annotations.SerializedName
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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
    @SerializedName("version")
    val version: Int = 1,
    @SerializedName("updatedAt")
    val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("items")
    val items: List<FavoriteArticle>? = emptyList()
)

data class FavoriteArticle(
    @SerializedName("id")
    val id: Int,
    @SerializedName("title")
    val title: String?,
    @SerializedName("link")
    val link: String?,
    @SerializedName("image")
    val image: String?,
    @SerializedName("content")
    val content: String?,
    @SerializedName("time")
    val time: Long?,
    @SerializedName("comments")
    val comments: Int,
    @SerializedName("author")
    val author: Tag?,
    @SerializedName("category")
    val category: Tag?,
    @SerializedName("tags")
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

object FavoriteWebDav {
    private const val URL = "favorite.webdav.url"
    private const val USERNAME = "favorite.webdav.username"
    private const val PASSWORD = "favorite.webdav.password"
    private const val PATH = "favorite.webdav.path"
    private const val DEFAULT_PATH = "hacg-favorites.json"

    private val preference
        get() = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance)

    fun config(): FavoriteWebDavConfig = FavoriteWebDavConfig(
        preference.getString(URL, "") ?: "",
        preference.getString(USERNAME, "") ?: "",
        preference.getString(PASSWORD, "") ?: "",
        preference.getString(PATH, DEFAULT_PATH)?.ifBlank { DEFAULT_PATH } ?: DEFAULT_PATH
    )

    fun save(config: FavoriteWebDavConfig) {
        preference.edit()
            .putString(URL, config.url.trim())
            .putString(USERNAME, config.username.trim())
            .putString(PASSWORD, config.password)
            .putString(PATH, config.path.trim().ifBlank { DEFAULT_PATH })
            .apply()
    }

    fun upload(config: FavoriteWebDavConfig, json: String) {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = request(config).put(body).build()
        okhttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("WebDAV ${response.code}: ${response.message}")
        }
    }

    fun download(config: FavoriteWebDavConfig): String {
        val request = request(config).get().build()
        okhttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("WebDAV ${response.code}: ${response.message}")
            return response.body?.string() ?: throw IOException("WebDAV empty response")
        }
    }

    private fun request(config: FavoriteWebDavConfig): Request.Builder =
        Request.Builder().url(config.fileUrl()).apply {
            if (config.username.isNotBlank() || config.password.isNotBlank()) {
                header("Authorization", Credentials.basic(config.username, config.password))
            }
        }
}

data class FavoriteWebDavConfig(
    val url: String,
    val username: String,
    val password: String,
    val path: String
) {
    val ready: Boolean get() = url.isNotBlank() && path.isNotBlank()

    fun fileUrl(): String = "${url.trim().trimEnd('/')}/${path.trim().trimStart('/')}"
}
