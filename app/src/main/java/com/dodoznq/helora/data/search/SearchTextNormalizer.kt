package com.dodoznq.helora.data.search

import java.text.Normalizer
import java.util.Locale

/** Simplifies search text for comparison: lowercase, no diacritics, no punctuation. */
object SearchTextNormalizer {

    private val diacriticsRegex = Regex("\\p{Mn}+")

    // Trailing noise commonly appended to YouTube/local titles: "(Official Video)",
    // "[Lyrics]", "(Remastered 2011)", "- Topic". Applied after lowercasing, so all
    // alternatives here are lowercase. Re-applied until stable to strip stacked
    // suffixes like "(Live) (Remastered 2011)".
    private val trailingNoiseRegex = Regex(
        "(?:[(\\[]\\s*(?:official\\s*(?:music\\s*)?video|official\\s*(?:audio|lyric\\s*video)?|" +
            "lyrics?(?:\\s*video)?|remaster(?:ed)?(?:\\s*\\d{4})?|hd|4k|visualizer)\\s*[)\\]]|" +
            "\\s*-\\s*topic)\\s*$"
    )

    private val punctuationRegex = Regex("[^\\p{L}\\p{N}\\s]+")
    private val whitespaceRegex = Regex("\\s+")

    fun normalize(raw: String): String {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFD)
        text = diacriticsRegex.replace(text, "")
        text = text.lowercase(Locale.ROOT)

        var previous: String
        do {
            previous = text
            text = trailingNoiseRegex.replace(text, "")
        } while (text != previous)

        text = punctuationRegex.replace(text, " ")
        text = whitespaceRegex.replace(text, " ")
        return text.trim()
    }
}
