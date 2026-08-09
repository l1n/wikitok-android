package com.novalinium.wikitok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("$lang.wikipedia.org")
                .addPathSegments("w/api.php")
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("generator", "random")
                .addQueryParameter("grnnamespace", "0")
                .addQueryParameter("grnlimit", limit.toString())
                .addQueryParameter("prop", "extracts|pageimages|info")
                .addQueryParameter("inprop", "url")
                .addQueryParameter("exintro", "1")
                .addQueryParameter("explaintext", "1")
                .addQueryParameter("exchars", "1000")
                .addQueryParameter("piprop", "thumbnail")
                .addQueryParameter("pithumbsize", "1080")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "WikiTok-Android/1.0 (personal project)")
                .build()
            val body = client.newCall(request).await()
            parseBatch(body, lang)
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
