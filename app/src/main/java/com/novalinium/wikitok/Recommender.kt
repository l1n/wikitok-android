package com.novalinium.wikitok

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
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
    companion object {
        const val WEIGHT_LIKE = 0.5f
        const val WEIGHT_EXPAND = 0.15f

        /** Per-second dwell credit; 30s of reading ≈ a quarter of a like. */
        const val WEIGHT_DWELL_PER_SEC = 0.004f
        const val DWELL_MIN_SEC = 3f
        const val DWELL_CAP_SEC = 30f

        /** Per-event decay keeps the profile drifting toward recent interests. */
        private const val DECAY = 0.99f
    }

    // v2: embedding space changed from MiniLM to our Wikipedia-trained model
    private val profileKey = stringPreferencesKey("user_profile_v2")
    private var profile: FloatArray? = null
    private var profileLoaded = false

    @Volatile
    private var embedder: WikiEmbedder? = null
    private var embedderFailed = false
    private val initMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun ensureReady(): Boolean {
        embedder?.let { return true }
        if (embedderFailed) return false
        return initMutex.withLock {
            embedder?.let { return true }
            if (embedderFailed) return false
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { WikiEmbedder(context.assets.open("wiki_embeddings.bin")) }
                    .onSuccess {
                        embedder = it
                        Log.d("WikiTok", "embedder loaded (dim=${it.dim})")
                    }
                    .onFailure {
                        Log.e("WikiTok", "embedder load failed", it)
                        embedderFailed = true
                    }
                    .isSuccess
            }
        }
    }
    private val cache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) =
            size > 512
    }

    private suspend fun loadProfile(): FloatArray? {
        if (!profileLoaded) {
            profileLoaded = true
            profile = context.wikitokDataStore.data.first()[profileKey]
                ?.split(',')?.map { it.toFloat() }?.toFloatArray()
                ?.takeIf { it.size == embedder?.dim }
        }
        return profile
    }

    private fun embeddingText(article: Article) =
        article.title + ". " + article.extract.take(300)

    private suspend fun embedCached(article: Article): FloatArray {
        val key = "${article.lang}-${article.pageid}"
        cache[key]?.let { return it }
        val e = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            embedder!!.embed(embeddingText(article), article.lang)
        }
        return e.also { cache[key] = it }
    }

    suspend fun onLiked(article: Article) = onEngaged(article, WEIGHT_LIKE, "like")

    suspend fun onExpanded(article: Article) = onEngaged(article, WEIGHT_EXPAND, "expand")

    suspend fun onDwell(article: Article, seconds: Float) {
        if (seconds < DWELL_MIN_SEC) return
        onEngaged(article, WEIGHT_DWELL_PER_SEC * seconds.coerceAtMost(DWELL_CAP_SEC), "dwell ${seconds.toInt()}s")
    }

    /**
     * Fold an engagement signal into the profile. The profile is a decaying
     * weighted sum of article embeddings (normalized only at ranking time), so
     * strong signals dominate weak ones in proportion to their weights.
     */
    private suspend fun onEngaged(article: Article, weight: Float, signal: String) {
        if (!ensureReady()) return
        runCatching {
            val e = embedCached(article)
            val p = loadProfile()
            val updated = FloatArray(embedder!!.dim) { i ->
                (p?.get(i) ?: 0f) * DECAY + weight * e[i]
            }
            profile = updated
            context.wikitokDataStore.edit { prefs ->
                prefs[profileKey] = updated.joinToString(",")
            }
            Log.d("WikiTok", "profile updated ($signal, w=%.3f): ${article.title}".format(weight))
        }.onFailure { Log.e("WikiTok", "onEngaged failed", it) }
    }

    /**
     * Order candidates by affinity to the profile. Passthrough until the user has
     * liked something and the model is ready. Every ~4th slot keeps a random pick.
     */
    suspend fun rank(candidates: List<Article>): List<Article> {
        if (candidates.size < 2) return candidates
        if (!ensureReady()) return candidates
        loadProfile() ?: return candidates
        return runCatching {
            val raw = profile ?: return candidates
            var pNorm = 0f
            for (x in raw) pNorm += x * x
            pNorm = sqrt(pNorm).coerceAtLeast(1e-9f)
            val p = FloatArray(raw.size) { raw[it] / pNorm }
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
