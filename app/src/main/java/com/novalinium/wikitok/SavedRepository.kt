package com.novalinium.wikitok

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "wikitok")

class SavedRepository(private val context: Context) {
    private val key = stringPreferencesKey("saved_articles")
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Article.serializer())

    val saved: Flow<List<Article>> = context.dataStore.data.map { prefs ->
        prefs[key]?.let {
            runCatching { json.decodeFromString(listSerializer, it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun toggle(article: Article) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString(listSerializer, it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val updated = if (current.any { it.pageid == article.pageid && it.lang == article.lang }) {
                current.filterNot { it.pageid == article.pageid && it.lang == article.lang }
            } else {
                listOf(article) + current
            }
            prefs[key] = json.encodeToString(listSerializer, updated)
        }
    }
}
