package com.novalinium.wikitok

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Monolith-shaped two-stage recommender, sized for a phone:
 *  - candidate generation happens upstream (random + morelike batches from the API);
 *  - ranking is a dot product between each candidate's MiniLM embedding and a user
 *    profile vector maintained as an exponential moving average of liked articles,
 *    with epsilon-greedy exploration so the feed never collapses into a bubble.
 */
class Recommender(private val context: Context) {
    private val embedder = ArticleEmbedder(context)
    private val profileKey = stringPreferencesKey("user_profile_v1")
    private var profile: FloatArray? = null
    private var profileLoaded = false
    private val cache = object : LinkedHashMap<Long, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FloatArray>) =
            size > 512
    }

    private suspend fun loadProfile(): FloatArray? {
        if (!profileLoaded) {
            profileLoaded = true
            profile = context.wikitokDataStore.data.first()[profileKey]
                ?.split(',')?.map { it.toFloat() }?.toFloatArray()
                ?.takeIf { it.size == ArticleEmbedder.DIM }
        }
        return profile
    }

    private fun embeddingText(article: Article) =
        article.title + ". " + article.extract.take(300)

    private suspend fun embedCached(article: Article): FloatArray {
        cache[article.pageid]?.let { return it }
        return embedder.embed(embeddingText(article)).also { cache[article.pageid] = it }
    }

    /** Fold a liked article into the user profile (EMA, then renormalize). */
    suspend fun onLiked(article: Article) {
        if (!embedder.ensureReady()) return
        runCatching {
            val e = embedCached(article)
            val p = loadProfile()
            val updated = if (p == null) e.copyOf() else FloatArray(ArticleEmbedder.DIM) { i ->
                0.8f * p[i] + 0.2f * e[i]
            }
            var norm = 0f
            for (x in updated) norm += x * x
            norm = sqrt(norm).coerceAtLeast(1e-9f)
            for (i in updated.indices) updated[i] /= norm
            profile = updated
            context.wikitokDataStore.edit { prefs ->
                prefs[profileKey] = updated.joinToString(",")
            }
            Log.d("WikiTok", "profile updated from like: ${article.title}")
        }.onFailure { Log.e("WikiTok", "onLiked failed", it) }
    }

    /**
     * Order candidates by affinity to the profile. Passthrough until the user has
     * liked something and the model is ready. Every ~4th slot keeps a random pick.
     */
    suspend fun rank(candidates: List<Article>): List<Article> {
        if (candidates.size < 2) return candidates
        loadProfile() ?: return candidates
        if (!embedder.ensureReady()) return candidates
        return runCatching {
            val p = profile ?: return candidates
            val scored = candidates.map { a ->
                var s = 0f
                val e = embedCached(a)
                for (i in e.indices) s += p[i] * e[i]
                a to s
            }.sortedByDescending { it.second }
            Log.d(
                "WikiTok",
                "ranked ${scored.size}: top=${scored.first().let { "${it.first.title} %.3f".format(it.second) }} " +
                    "bottom=${scored.last().let { "${it.first.title} %.3f".format(it.second) }}"
            )
            // Epsilon-greedy: reserve exploration slots for random tail candidates.
            val exploit = scored.map { it.first }.toMutableList()
            val result = mutableListOf<Article>()
            while (exploit.isNotEmpty()) {
                if (result.size % 4 == 3 && exploit.size > 1) {
                    result += exploit.removeAt(Random.nextInt(exploit.size))
                } else {
                    result += exploit.removeAt(0)
                }
            }
            result
        }.getOrDefault(candidates)
    }
}
