package com.novalinium.wikitok

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parity between WikiEmbedder and the Python reference (training/embed_ref.py):
 * testvectors.json is produced by training/export.py against the same
 * quantized table bundled in assets.
 */
class HashEmbedderTest {

    @Serializable
    data class Case(val lang: String, val text: String, val vector: List<Double>)

    @Test
    fun vectorsMatchPythonReference() {
        val asset = File("src/main/assets/wiki_embeddings.bin")
        val vectors = javaClass.getResource("/testvectors.json")
        assumeTrue("model asset + testvectors required", asset.exists() && vectors != null)
        val embedder = WikiEmbedder(asset.inputStream())
        val cases = Json { ignoreUnknownKeys = true }
            .decodeFromString<List<Case>>(vectors!!.readText())
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val v = embedder.embed(case.text, case.lang)
            assertEquals("dim for '${case.text}'", case.vector.size, v.size)
            for (i in v.indices) {
                assertEquals(
                    "component $i of '${case.text.take(30)}'",
                    case.vector[i],
                    v[i].toDouble(),
                    2e-4,
                )
            }
        }
    }

    @Test
    fun tokenizerHandlesCjkAndLatin() {
        assertEquals(
            listOf("apollo", "11", "was", "first"),
            WikiEmbedder.tokenize("Apollo 11 — was first!"),
        )
        assertEquals(
            listOf("物", "理", "学", "は", "重", "要"),
            WikiEmbedder.tokenize("物理学は重要"),
        )
    }
}
