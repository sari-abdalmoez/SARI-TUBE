package com.sari.tube.engine

/** Extracts http(s) links from free text (shared text often contains extra words). */
object UrlParser {
    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun extractUrls(text: String): List<String> =
        URL_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ';', '!') }
            .distinct()
            .toList()

    fun firstHttpUrl(text: String): String? = extractUrls(text).firstOrNull()
}
