package com.dodoznq.helora.utils

import java.text.Normalizer

/**
 * Compares artist names that came from different places.
 *
 * YouTube channel names, Deezer artist names and file tags never agree on accents, casing or
 * how featured artists are credited, so a plain string comparison says "different" far more
 * often than it should.
 */
object ArtistNameMatching {

    /**
     * Reduces a name to the part worth comparing: lowercase, unaccented, without bracketed
     * suffixes, without a featuring clause and without punctuation.
     *
     * [com.dodoznq.helora.data.youtube.YouTubeMusicRepository.normalizeForMatching]
     * looks similar but leaves accents alone, which loses "Baris Manco" against "Barış Manço".
     */
    fun normalize(value: String): String {
        // Dotless i survives decomposition, and it is common enough in Turkish names that
        // leaving it to be stripped as punctuation would break otherwise exact matches.
        val folded = value.lowercase().replace('ı', 'i')
        return Normalizer.normalize(folded, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .replace(BRACKETED, " ")
            .replace(FEATURING, " ")
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(WHITESPACE, " ")
    }

    /**
     * True when two names refer to the same act.
     *
     * One side may be a superset, because a channel is often credited with the featured
     * artists a search query leaves out. Containment is refused below
     * [MIN_CONTAINMENT_LENGTH], where a substring test stops meaning anything: "Mor" sits
     * inside "Mor ve Otesi" and a dozen unrelated acts.
     */
    fun matches(wanted: String, candidate: String): Boolean {
        val a = normalize(wanted)
        val b = normalize(candidate)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        if (shorter.length < MIN_CONTAINMENT_LENGTH) return false
        return a.contains(b) || b.contains(a)
    }

    private const val MIN_CONTAINMENT_LENGTH = 4

    private val BRACKETED = Regex("[\\(\\[][^\\)\\]]*[\\)\\]]")
    private val FEATURING = Regex("\\b(feat|ft|featuring|with|prod)\\b.*")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
}
