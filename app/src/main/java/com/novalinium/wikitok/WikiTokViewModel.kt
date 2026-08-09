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
    companion object {
        /** Set from the launch intent before the first composition (debug/testing hook). */
        var debugTitles: List<String>? = null
    }

    private val repo = SavedRepository(app)
    private val recommender = Recommender(app)

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
    private var batchCount = 0

    init {
        val debug = debugTitles
        if (debug != null) {
            viewModelScope.launch {
                runCatching { WikipediaApi.fetchByTitles(_language.value.code, debug) }
                    .onSuccess { batch ->
                        _feed.value = batch.filter { seen.add(it.pageid) } + _feed.value
                    }
            }
        } else {
            loadDailyHighlight()
        }
        loadMore()
    }

    fun ensureLoaded(currentIndex: Int) {
        if (_feed.value.size - currentIndex < 5) loadMore()
    }

    /** Prepend today's featured article, if this language edition publishes one. */
    private fun loadDailyHighlight() {
        val lang = _language.value.code
        viewModelScope.launch {
            val today = java.time.LocalDate.now()
            runCatching {
                WikipediaApi.fetchDailyFeatured(lang, today.year, today.monthValue, today.dayOfMonth)
            }.getOrNull()?.let { tfa ->
                if (_language.value.code == lang && seen.add(tfa.pageid)) {
                    val current = _feed.value
                    // Prepending after the user can see the feed shifts every pager
                    // index (and the visible page); slot in as the next card instead.
                    _feed.value = if (current.isEmpty()) listOf(tfa)
                    else listOf(current.first(), tfa) + current.drop(1)
                }
            }
        }
    }

    fun loadMore() {
        if (loading) return
        loading = true
        val lang = _language.value.code
        // Alternate random batches with morelike batches seeded from a saved article,
        // so the feed drifts toward what the user hearts.
        val seed = saved.value.filter { it.lang == lang && !it.isHighlight }
            .randomOrNull()?.takeIf { batchCount % 2 == 1 }
        batchCount++
        viewModelScope.launch {
            // A morelike batch can be fully deduped against `seen`; retry randomly
            // (bounded) so pagination never stalls.
            var useSeed = seed
            for (attempt in 1..3) {
                val result = runCatching {
                    useSeed?.let { s ->
                        WikipediaApi.fetchRelatedBatch(lang, s.title)
                            .ifEmpty { WikipediaApi.fetchRandomBatch(lang) }
                    } ?: WikipediaApi.fetchRandomBatch(lang)
                }
                val batch = result.getOrNull()
                if (batch == null) {
                    if (_feed.value.isEmpty()) {
                        _error.value = result.exceptionOrNull()?.message ?: "Network error"
                    }
                    break
                }
                if (_language.value.code != lang) break
                _error.value = null
                val fresh = recommender.rank(batch.filter { seen.add(it.pageid) })
                _feed.value = _feed.value + fresh
                if (fresh.isNotEmpty()) break
                useSeed = null
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
        batchCount = 0
        loadDailyHighlight()
        loadMore()
    }

    fun toggleSaved(article: Article) {
        val wasLiked = isSaved(article, saved.value)
        viewModelScope.launch {
            repo.toggle(article)
            // Only new likes teach the recommender; unliking doesn't unlearn.
            if (!wasLiked) recommender.onLiked(article)
        }
    }

    fun onDwell(article: Article, seconds: Float) {
        viewModelScope.launch { recommender.onDwell(article, seconds) }
    }

    fun onExtractExpanded(article: Article) {
        viewModelScope.launch { recommender.onExpanded(article) }
    }

    fun isSaved(article: Article, savedList: List<Article>): Boolean =
        savedList.any { it.pageid == article.pageid && it.lang == article.lang }
}
