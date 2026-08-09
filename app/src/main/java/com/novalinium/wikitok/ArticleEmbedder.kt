package com.novalinium.wikitok

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.sqrt

/**
 * On-device sentence embeddings via all-MiniLM-L6-v2 (int8 ONNX, 384 dims).
 * Model + vocab (~24MB) are fetched once into filesDir; until they're present
 * the embedder reports not-ready and the recommender falls back to passthrough.
 */
class ArticleEmbedder(private val context: Context) {
    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"
        private const val VOCAB_URL =
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt"
        const val DIM = 384
        private const val MAX_TOKENS = 128
    }

    private val client = OkHttpClient()
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var vocab: Map<String, Long>? = null
    private var clsId = 0L
    private var sepId = 0L
    private var failed = false

    suspend fun ensureReady(): Boolean = mutex.withLock {
        if (session != null) return true
        if (failed) return false
        withContext(Dispatchers.IO) {
            runCatching {
                val modelFile = File(context.filesDir, "minilm_quantized.onnx")
                val vocabFile = File(context.filesDir, "minilm_vocab.txt")
                if (!modelFile.exists()) download(MODEL_URL, modelFile)
                if (!vocabFile.exists()) download(VOCAB_URL, vocabFile)
                val v = HashMap<String, Long>(32768)
                vocabFile.readLines().forEachIndexed { i, tok -> v[tok] = i.toLong() }
                vocab = v
                clsId = v["[CLS]"] ?: error("no [CLS] in vocab")
                sepId = v["[SEP]"] ?: error("no [SEP] in vocab")
                session = OrtEnvironment.getEnvironment()
                    .createSession(modelFile.absolutePath, OrtSession.SessionOptions())
                Log.d("WikiTok", "embedder ready (vocab ${v.size})")
            }.onFailure {
                Log.e("WikiTok", "embedder init failed", it)
                failed = true
            }.isSuccess
        }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "WikiTok-Android/1.0 (personal project)").build()
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            tmp.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        check(tmp.renameTo(dest)) { "rename failed for $dest" }
        Log.d("WikiTok", "downloaded ${dest.name} (${dest.length() / 1024}KB)")
    }

    /** Mean-pooled, L2-normalized embedding. Call only after ensureReady() == true. */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val sess = session ?: error("embedder not ready")
        val ids = mutableListOf(clsId)
        tokenize(text).take(MAX_TOKENS - 2).forEach { ids += it }
        ids += sepId
        val n = ids.size
        val env = OrtEnvironment.getEnvironment()
        val inputIds = OnnxTensor.createTensor(env, arrayOf(ids.toLongArray()))
        val mask = OnnxTensor.createTensor(env, arrayOf(LongArray(n) { 1L }))
        val types = OnnxTensor.createTensor(env, arrayOf(LongArray(n) { 0L }))
        inputIds.use { i ->
            mask.use { m ->
                types.use { t ->
                    sess.run(
                        mapOf("input_ids" to i, "attention_mask" to m, "token_type_ids" to t)
                    ).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = out[0].value as Array<Array<FloatArray>>
                        val pooled = FloatArray(DIM)
                        for (tok in hidden[0]) for (d in 0 until DIM) pooled[d] += tok[d]
                        var norm = 0f
                        for (d in 0 until DIM) {
                            pooled[d] /= n
                            norm += pooled[d] * pooled[d]
                        }
                        norm = sqrt(norm).coerceAtLeast(1e-9f)
                        for (d in 0 until DIM) pooled[d] /= norm
                        pooled
                    }
                }
            }
        }
    }

    /** Lowercasing basic tokenizer + greedy WordPiece. */
    private fun tokenize(text: String): List<Long> {
        val v = vocab ?: return emptyList()
        val unk = v["[UNK]"] ?: 100L
        val words = text.lowercase()
            .map { c -> if (c.isLetterOrDigit()) c else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val out = mutableListOf<Long>()
        for (word in words) {
            if (word.length > 100) { out += unk; continue }
            var start = 0
            val pieces = mutableListOf<Long>()
            var ok = true
            while (start < word.length) {
                var end = word.length
                var id: Long? = null
                while (end > start) {
                    val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                    val hit = v[sub]
                    if (hit != null) { id = hit; break }
                    end--
                }
                if (id == null) { ok = false; break }
                pieces += id
                start = end
            }
            if (ok) out += pieces else out += unk
        }
        return out
    }
}
