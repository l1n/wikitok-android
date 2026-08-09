package com.novalinium.wikitok

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Language(val code: String, val label: String)

val LANGUAGES = listOf(
    Language("en", "English"),
    Language("es", "Español"),
    Language("fr", "Français"),
    Language("de", "Deutsch"),
    Language("pt", "Português"),
    Language("it", "Italiano"),
    Language("ru", "Русский"),
    Language("ja", "日本語"),
    Language("zh", "中文"),
    Language("pl", "Polski"),
)

class WikiTokViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SavedRepository(app)

    private val _feed = MutableStateFlow<List<Article>>(emptyList())
    val feed: StateFlow<List<Article>> = _feed

    private val _language = MutableStateFlow(LANGUAGES.first())
    val language: StateFlow<Language> = _language

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val saved: StateFlow<List<Article>> = repo.saved
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val seen = mutableSetOf<Long>()
    private var loading = false

    init {
        loadMore()
    }

    fun ensureLoaded(currentIndex: Int) {
        if (_feed.value.size - currentIndex < 5) loadMore()
    }

    fun loadMore() {
        if (loading) return
        loading = true
        val lang = _language.value.code
        viewModelScope.launch {
            runCatching { WikipediaApi.fetchRandomBatch(lang) }
                .onSuccess { batch ->
                    if (_language.value.code == lang) {
                        _error.value = null
                        val fresh = batch.filter { seen.add(it.pageid) }
                        _feed.value = _feed.value + fresh
                    }
                }
                .onFailure { e ->
                    if (_feed.value.isEmpty()) _error.value = e.message ?: "Network error"
                }
            loading = false
        }
    }

    fun setLanguage(language: Language) {
        if (language == _language.value) return
        _language.value = language
        seen.clear()
        _feed.value = emptyList()
        _error.value = null
        loadMore()
    }

    fun toggleSaved(article: Article) {
        viewModelScope.launch { repo.toggle(article) }
    }

    fun isSaved(article: Article, savedList: List<Article>): Boolean =
        savedList.any { it.pageid == article.pageid && it.lang == article.lang }
}
