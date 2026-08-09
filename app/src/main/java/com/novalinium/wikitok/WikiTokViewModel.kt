package com.novalinium.wikitok

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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

    private val _languages = MutableStateFlow(listOf(LANGUAGES.first()))
    val languages: StateFlow<List<Language>> = _languages

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val saved: StateFlow<List<Article>> = repo.saved
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val seen = mutableSetOf<String>()
    private var loading = false
    private var batchCount = 0

    init {
        val debug = debugTitles
        if (debug != null) {
            viewModelScope.launch {
                runCatching { WikipediaApi.fetchByTitles(primaryLang(), debug) }
                    .onSuccess { batch ->
                        _feed.value = batch.filter { seen.add(key(it)) } + _feed.value
                    }
            }
        } else {
            loadDailyHighlight()
        }
        loadMore()
        // Restore the persisted language selection (also read by the digest worker).
        viewModelScope.launch {
            val prefs = getApplication<Application>().wikitokDataStore.data.first()
            val stored = prefs[stringPreferencesKey("languages")]?.split(',')
                ?: prefs[stringPreferencesKey("language")]?.let { listOf(it) }
            val restored = stored?.mapNotNull { code -> LANGUAGES.find { it.code == code } }
            if (!restored.isNullOrEmpty() && restored != _languages.value) {
                _languages.value = restored
                resetAndReload()
            }
        }
    }

    fun ensureLoaded(currentIndex: Int) {
        if (_feed.value.size - currentIndex < 5) loadMore()
    }

    private fun primaryLang() = _languages.value.first().code

    private fun key(article: Article) = "${article.lang}-${article.pageid}"

    /** Prepend today's featured article from the primary language edition. */
    private fun loadDailyHighlight() {
        val lang = primaryLang()
        viewModelScope.launch {
            val today = java.time.LocalDate.now()
            runCatching {
                WikipediaApi.fetchDailyFeatured(lang, today.year, today.monthValue, today.dayOfMonth)
            }.getOrNull()?.let { tfa ->
                if (primaryLang() == lang && seen.add(key(tfa))) {
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
        val langs = _languages.value.map { it.code }
        // Alternate random batches with morelike batches seeded from a saved article,
        // so the feed drifts toward what the user hearts.
        val seed = saved.value.filter { it.lang in langs && !it.isHighlight }
            .randomOrNull()?.takeIf { batchCount % 2 == 1 }
        batchCount++
        viewModelScope.launch {
            // A morelike batch can be fully deduped against `seen`; retry randomly
            // (bounded) so pagination never stalls.
            var useSeed = seed
            for (attempt in 1..3) {
                val s = useSeed
                val perLang = (24 / langs.size).coerceAtLeast(8)
                val result = runCatching {
                    coroutineScope {
                        langs.map { lang ->
                            async {
                                if (s != null && s.lang == lang) {
                                    WikipediaApi.fetchRelatedBatch(lang, s.title, perLang)
                                        .ifEmpty { WikipediaApi.fetchRandomBatch(lang, perLang) }
                                } else {
                                    WikipediaApi.fetchRandomBatch(lang, perLang)
                                }
                            }
                        }.map { it.await() }
                    }
                }
                val batches = result.getOrNull()
                if (batches == null) {
                    if (_feed.value.isEmpty()) {
                        _error.value = result.exceptionOrNull()?.message ?: "Network error"
                    }
                    break
                }
                if (_languages.value.map { it.code } != langs) break
                _error.value = null
                val fresh = recommender.rank(
                    interleave(batches).filter { seen.add(key(it)) }
                )
                _feed.value = _feed.value + fresh
                if (fresh.isNotEmpty()) break
                useSeed = null
            }
            loading = false
        }
    }

    /** Round-robin across per-language batches so no language dominates a stretch. */
    private fun interleave(batches: List<List<Article>>): List<Article> {
        val result = mutableListOf<Article>()
        val iterators = batches.map { it.iterator() }
        var added = true
        while (added) {
            added = false
            for (iter in iterators) {
                if (iter.hasNext()) {
                    result += iter.next()
                    added = true
                }
            }
        }
        return result
    }

    fun toggleLanguage(language: Language) {
        val current = _languages.value
        val updated = when {
            language !in current -> current + language
            current.size == 1 -> return // at least one language stays selected
            else -> current - language
        }
        _languages.value = updated
        viewModelScope.launch {
            getApplication<Application>().wikitokDataStore.edit { prefs ->
                prefs[stringPreferencesKey("languages")] =
                    updated.joinToString(",") { it.code }
            }
        }
        // Keep still-selected articles in place; just top up with the new mix.
        val codes = updated.map { it.code }.toSet()
        _feed.value = _feed.value.filter { it.lang in codes }
        _error.value = null
        loadMoreSoon()
    }

    /** loadMore that waits out any in-flight load instead of being swallowed by it. */
    private fun loadMoreSoon() {
        viewModelScope.launch {
            while (loading) kotlinx.coroutines.delay(100)
            loadMore()
        }
    }

    private fun resetAndReload() {
        seen.clear()
        _feed.value = emptyList()
        _error.value = null
        batchCount = 0
        loadDailyHighlight()
        loadMoreSoon()
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
