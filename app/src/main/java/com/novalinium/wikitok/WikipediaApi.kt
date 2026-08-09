package com.novalinium.wikitok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WikipediaApi {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchRandomBatch(lang: String, limit: Int = 20): List<Article> =
        withContext(Dispatchers.IO) {
            val url = queryUrl(lang)
                .addQueryParameter("generator", "random")
                .addQueryParameter("grnnamespace", "0")
                .addQueryParameter("grnlimit", limit.toString())
                .build()
            attachVideos(parseBatch(get(url), lang), lang)
        }

    /** Fetch specific articles by title (debug/deep-link path). */
    suspend fun fetchByTitles(lang: String, titles: List<String>): List<Article> =
        withContext(Dispatchers.IO) {
            val url = queryUrl(lang)
                .addQueryParameter("titles", titles.joinToString("|"))
                .build()
            attachVideos(parseBatch(get(url), lang), lang)
        }

    /** Articles similar to [title] via CirrusSearch morelike — the recommendation feed. */
    suspend fun fetchRelatedBatch(lang: String, title: String, limit: Int = 20): List<Article> =
        withContext(Dispatchers.IO) {
            val url = queryUrl(lang)
                .addQueryParameter("generator", "search")
                .addQueryParameter("gsrsearch", "morelike:$title")
                .addQueryParameter("gsrnamespace", "0")
                .addQueryParameter("gsrlimit", limit.toString())
                .build()
            attachVideos(parseBatch(get(url), lang), lang)
        }

    private val videoExtensions = setOf("webm", "ogv", "mpg", "mpeg", "mp4")

    /**
     * Find Commons videos embedded in the batch's articles and attach a playable
     * (transcoded) URL. Two extra requests per batch: page→files, then file→derivatives.
     */
    private suspend fun attachVideos(articles: List<Article>, lang: String): List<Article> {
        if (articles.isEmpty()) return articles
        return runCatching {
            val filesUrl = HttpUrl.Builder()
                .scheme("https")
                .host("$lang.wikipedia.org")
                .addPathSegments("w/api.php")
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("pageids", articles.joinToString("|") { it.pageid.toString() })
                .addQueryParameter("prop", "images")
                .addQueryParameter("imlimit", "500")
                .build()
            val pagesObj = json.parseToJsonElement(get(filesUrl)).jsonObject["query"]
                ?.jsonObject?.get("pages")?.jsonObject ?: return articles
            val videoByPage = mutableMapOf<Long, String>()
            for ((id, page) in pagesObj) {
                val images = page.jsonObject["images"]?.jsonArray ?: continue
                val file = images.mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content }
                    .firstOrNull { it.substringAfterLast('.').lowercase() in videoExtensions }
                if (file != null) id.toLongOrNull()?.let { videoByPage[it] = file }
            }
            if (videoByPage.isEmpty()) return articles

            val infoUrl = HttpUrl.Builder()
                .scheme("https")
                .host("$lang.wikipedia.org")
                .addPathSegments("w/api.php")
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("titles", videoByPage.values.distinct().take(50).joinToString("|"))
                .addQueryParameter("prop", "videoinfo")
                .addQueryParameter("viprop", "derivatives")
                .build()
            val infoPages = json.parseToJsonElement(get(infoUrl)).jsonObject["query"]
                ?.jsonObject?.get("pages")?.jsonObject ?: return articles
            val urlByFile = mutableMapOf<String, String>()
            for (page in infoPages.values) {
                val obj = page.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: continue
                val derivatives = obj["videoinfo"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("derivatives")?.jsonArray ?: continue
                data class Deriv(val url: String, val height: Int, val mime: String)
                val options = derivatives.mapNotNull { d ->
                    val o = d.jsonObject
                    val src = o["src"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    Deriv(
                        url = src,
                        height = o["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        mime = o["type"]?.jsonPrimitive?.content ?: "",
                    )
                }
                // Highest-quality transcode that stays phone-sized; originals can be huge.
                val best = options
                    .filter { it.height in 1..720 && ("mp4" in it.mime || "webm" in it.mime) }
                    .maxByOrNull { it.height }
                    ?: options.filter { "mp4" in it.mime || "webm" in it.mime }.minByOrNull { it.height }
                if (best != null) urlByFile[title] = best.url
            }
            android.util.Log.d("WikiTok", "videos: ${videoByPage.size} pages, resolved ${urlByFile.size}")
            articles.map { a ->
                val url = videoByPage[a.pageid]?.let { urlByFile[it] }
                if (url != null) a.copy(videoUrl = url) else a
            }
        }.getOrDefault(articles)
    }

    /** Wikipedia's featured article of the day (not available for every language). */
    suspend fun fetchDailyFeatured(lang: String, year: Int, month: Int, day: Int): Article? =
        withContext(Dispatchers.IO) {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("$lang.wikipedia.org")
                .addPathSegments("api/rest_v1/feed/featured")
                .addPathSegment("%04d".format(year))
                .addPathSegment("%02d".format(month))
                .addPathSegment("%02d".format(day))
                .build()
            val tfa = json.parseToJsonElement(get(url)).jsonObject["tfa"]?.jsonObject
                ?: return@withContext null
            val pageid = tfa["pageid"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: return@withContext null
            Article(
                pageid = pageid,
                title = tfa["titles"]?.jsonObject?.get("normalized")?.jsonPrimitive?.content
                    ?: tfa["title"]?.jsonPrimitive?.content ?: return@withContext null,
                extract = tfa["extract"]?.jsonPrimitive?.content?.trim() ?: "",
                thumbnail = tfa["thumbnail"]?.let {
                    runCatching { json.decodeFromJsonElement(Thumbnail.serializer(), it) }.getOrNull()
                },
                fullurl = tfa["content_urls"]?.jsonObject?.get("desktop")?.jsonObject
                    ?.get("page")?.jsonPrimitive?.content,
                lang = lang,
                isHighlight = true,
            )
        }

    private fun queryUrl(lang: String): HttpUrl.Builder = HttpUrl.Builder()
        .scheme("https")
        .host("$lang.wikipedia.org")
        .addPathSegments("w/api.php")
        .addQueryParameter("action", "query")
        .addQueryParameter("format", "json")
        .addQueryParameter("prop", "extracts|pageimages|info")
        .addQueryParameter("inprop", "url")
        .addQueryParameter("exintro", "1")
        .addQueryParameter("explaintext", "1")
        .addQueryParameter("exchars", "1000")
        .addQueryParameter("piprop", "thumbnail")
        .addQueryParameter("pithumbsize", "1080")
        // Include non-free lead images (album covers, film posters, portraits):
        // the default free-only policy leaves ~half of articles imageless.
        .addQueryParameter("pilicense", "any")

    private suspend fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WikiTok-Android/1.0 (personal project)")
            .build()
        return client.newCall(request).await()
    }

    private fun parseBatch(body: String, lang: String): List<Article> {
        val root = json.parseToJsonElement(body).jsonObject
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
        val result = pages.values.mapNotNull { page ->
            val obj = page.jsonObject
            val pageid = obj["pageid"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Article(
                pageid = pageid,
                title = title,
                extract = obj["extract"]?.jsonPrimitive?.content?.trim() ?: "",
                thumbnail = obj["thumbnail"]?.let {
                    runCatching { json.decodeFromJsonElement(Thumbnail.serializer(), it) }.getOrNull()
                },
                fullurl = obj["fullurl"]?.jsonPrimitive?.content,
                lang = lang,
            )
        }
        android.util.Log.d(
            "WikiTok",
            "parsed batch: ${result.size} articles, ${result.count { it.thumbnail != null }} with thumbnail"
        )
        return result
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (cont.isActive) cont.resumeWithException(IOException("HTTP ${it.code}"))
                    } else {
                        val text = it.body?.string() ?: ""
                        if (cont.isActive) cont.resume(text)
                    }
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
