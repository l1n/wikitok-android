package com.novalinium.wikitok

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.sqrt

/**
 * Pure-Kotlin multilingual article embedder over a hash-bucket subword table
 * trained on Wikipedia dumps (see training/). Mirrors training/common.py and
 * training/embed_ref.py exactly — verified by HashEmbedderTest against
 * testvectors.json exported alongside the weights.
 */
class WikiEmbedder(stream: InputStream) {
    val dim: Int
    private val buckets: Int
    private val mean: FloatArray
    private val scales: FloatArray
    private val table: ByteArray
    private val freqs: Map<String, Map<String, Long>>
    private val totals: Map<String, Long>

    init {
        DataInputStream(stream.buffered(1 shl 20)).use { d ->
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic) == "WKEM") { "bad magic" }
            require(d.readInt() == 2) { "unsupported version" }
            dim = d.readInt()
            buckets = d.readInt()
            mean = FloatArray(dim) { d.readFloat() }
            scales = FloatArray(buckets) { d.readFloat() }
            table = ByteArray(buckets * dim).also { d.readFully(it) }
            val langCount = d.readInt()
            val f = HashMap<String, Map<String, Long>>(langCount)
            val t = HashMap<String, Long>(langCount)
            repeat(langCount) {
                val code = ByteArray(d.readUnsignedByte()).also { b -> d.readFully(b) }
                    .toString(Charsets.UTF_8)
                t[code] = d.readLong()
                val n = d.readInt()
                val m = HashMap<String, Long>(n * 2)
                repeat(n) {
                    val tok = ByteArray(d.readUnsignedShort()).also { b -> d.readFully(b) }
                        .toString(Charsets.UTF_8)
                    m[tok] = d.readLong()
                }
                f[code] = m
            }
            freqs = f
            totals = t
        }
    }

    companion object {
        private const val NGRAM_MIN = 3
        private const val NGRAM_MAX = 5
        private const val MAX_TOKEN_LEN = 30
        private const val MAX_NGRAMS = 40
        private const val SIF_A = 1e-3
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
        private const val FNV_PRIME = 0x100000001B3L

        // Android's regex rejects UNICODE_CHARACTER_CLASS, so tokenization is an
        // explicit code-point walk matching Python's \w (isalnum = L* + Nd/Nl/No).
        // CJK ranges as in common.py: kana, CJK ext A, CJK unified, CJK compat.
        private fun isCjk(cp: Int) =
            cp in 0x3040..0x30FF || cp in 0x3400..0x4DBF ||
                cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF

        private fun isAlnum(cp: Int): Boolean {
            if (Character.isLetter(cp)) return true
            val t = Character.getType(cp)
            return t == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                t == Character.LETTER_NUMBER.toInt() ||
                t == Character.OTHER_NUMBER.toInt()
        }

        fun tokenize(text: String): List<String> {
            val lower = text.lowercase()
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < lower.length) {
                val cp = lower.codePointAt(i)
                when {
                    isCjk(cp) -> {
                        if (sb.isNotEmpty()) {
                            out.add(sb.toString())
                            sb.setLength(0)
                        }
                        out.add(String(Character.toChars(cp)))
                    }
                    isAlnum(cp) -> sb.appendCodePoint(cp)
                    else -> if (sb.isNotEmpty()) {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                }
                i += Character.charCount(cp)
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }

        private fun fnv1a(data: ByteArray): Long {
            var h = FNV_OFFSET
            for (b in data) {
                h = h xor (b.toInt() and 0xFF).toLong()
                h *= FNV_PRIME
            }
            return h
        }

        fun tokenNgrams(token: String): List<String> {
            val t = if (token.length > MAX_TOKEN_LEN) token.substring(0, MAX_TOKEN_LEN) else token
            val wrapped = "<$t>"
            val grams = ArrayList<String>()
            grams.add(wrapped)
            val n = wrapped.length
            for (size in NGRAM_MIN..NGRAM_MAX) {
                if (size >= n) continue
                for (i in 0..n - size) {
                    grams.add(wrapped.substring(i, i + size))
                    if (grams.size >= MAX_NGRAMS) return grams
                }
            }
            return grams
        }
    }

    private fun bucketOf(s: String): Int =
        ((fnv1a(s.toByteArray(Charsets.UTF_8)) and Long.MAX_VALUE) % buckets).toInt()

    private fun addTokenVec(token: String, acc: DoubleArray, weight: Double) {
        val grams = tokenNgrams(token)
        val tokenAcc = DoubleArray(dim)
        for (g in grams) {
            val b = bucketOf(g)
            val scale = scales[b]
            val off = b * dim
            for (i in 0 until dim) tokenAcc[i] += table[off + i] * scale
        }
        val inv = weight / grams.size
        for (i in 0 until dim) acc[i] += tokenAcc[i] * inv
    }

    /** SIF-weighted mean of subword token vectors, L2-normalized. */
    fun embed(text: String, lang: String): FloatArray {
        val tokens = tokenize(text)
        val out = FloatArray(dim)
        if (tokens.isEmpty()) return out
        val freq = freqs[lang] ?: emptyMap()
        val total = totals[lang] ?: 0L
        val acc = DoubleArray(dim)
        var wsum = 0.0
        for (t in tokens) {
            val c = freq[t] ?: 0L
            val w = if (c <= 0L || total <= 0L) 1.0 else SIF_A / (SIF_A + c.toDouble() / total)
            addTokenVec(t, acc, w)
            wsum += w
        }
        var norm = 0.0
        for (i in 0 until dim) {
            acc[i] /= maxOf(wsum, 1e-9)
            norm += acc[i] * acc[i]
        }
        norm = maxOf(sqrt(norm), 1e-9)
        // Normalize, remove the common component, normalize again (SIF-style)
        var cnorm = 0.0
        for (i in 0 until dim) {
            acc[i] = acc[i] / norm - mean[i]
            cnorm += acc[i] * acc[i]
        }
        cnorm = maxOf(sqrt(cnorm), 1e-9)
        for (i in 0 until dim) out[i] = (acc[i] / cnorm).toFloat()
        return out
    }
}
