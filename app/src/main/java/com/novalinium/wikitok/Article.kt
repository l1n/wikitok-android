package com.novalinium.wikitok

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnail(
    val source: String,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class Article(
    val pageid: Long,
    val title: String,
    val extract: String = "",
    val thumbnail: Thumbnail? = null,
    val fullurl: String? = null,
    val lang: String = "en",
    val isHighlight: Boolean = false,
    val videoUrl: String? = null,
) {
    val url: String
        get() = fullurl ?: "https://$lang.wikipedia.org/?curid=$pageid"
}
