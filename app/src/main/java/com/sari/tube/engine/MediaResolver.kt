package com.sari.tube.engine

import java.net.URL

/** One possible way to get the file. The service tries them in order. */
data class Candidate(
    val url: String,
    val headers: Map<String, String>,
    val label: String,
    val fileName: String,
    val mime: String
)

object MediaResolver {
    private val MEDIA_EXT = setOf("mp4", "webm", "mkv", "m4v", "mp3", "m4a", "aac", "wav", "flac", "ogg")

    /**
     * Ordered candidates: DoodStream extraction first (when applicable),
     * then the link itself as a direct download. Auto-switches when one is rejected.
     */
    fun candidates(input: String): List<Candidate> {
        val pageUrl = UrlParser.firstHttpUrl(input)
            ?: DoodResolver.extractId(input)?.let { "https://dood.to/e/$it" }
            ?: return emptyList()

        val result = mutableListOf<Candidate>()

        // DoodStream must be resolved to its actual media URL.
        // Never send its embed page to the direct-file downloader.
        if (DoodResolver.isDoodUrl(pageUrl)) {
            DoodResolver.resolve(pageUrl)?.let { r ->
                val id = DoodResolver.extractId(pageUrl) ?: "video"
                result += Candidate(
                    r.url,
                    r.headers,
                    "DoodStream",
                    "doodstream_$id.mp4",
                    "video/mp4"
                )
            }
            return result
        }

        val parsed = URL(pageUrl)
        val last = parsed.path.substringAfterLast('/').substringBefore('?')
        val ext = last.substringAfterLast('.', "").lowercase()

        // A webpage is not a direct media URL.
        if (ext !in MEDIA_EXT) return emptyList()

        val origin = "${parsed.protocol}://${parsed.host}/"
        val name = sanitize(last)

        result += Candidate(
            pageUrl,
            mapOf("Referer" to origin),
            "مباشر",
            name,
            mimeFor(name)
        )
        return result
    }

    fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> "video/mp4"
    }
}
