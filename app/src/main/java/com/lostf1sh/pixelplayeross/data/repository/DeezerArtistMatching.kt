package com.lostf1sh.pixelplayeross.data.repository

import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerArtist
import java.text.Normalizer

/**
 * Shared rules for lining a local name up with a Deezer result.
 *
 * Two callers need the same normalizer and Deezer has the same trap for both: a search always
 * answers, and the first answer is not always the right one.
 */
internal object DeezerArtistMatching {

    /**
     * Reduces a name to the part worth comparing: lowercase, unaccented, without bracketed
     * suffixes like "(Official Video)" or "(Radio Edit)", without a featuring clause, and
     * without punctuation. "HUMBLE." and "Humble" come out the same, and so do "Gülümse" and
     * "Gulumse".
     *
     * [com.lostf1sh.pixelplayeross.data.youtube.YouTubeMusicRepository.normalizeForMatching]
     * looks similar but does not fold accents, which is what Turkish names need here.
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
     * True when two artist names refer to the same act.
     *
     * One side may be a superset, because Deezer credits featured artists in the name where
     * YouTube often does not. Containment is refused below [MIN_CONTAINMENT_LENGTH], where a
     * substring test stops meaning anything: "Mor" is inside "Mor ve Otesi" and a dozen
     * unrelated acts.
     */
    fun artistMatches(wanted: String, candidate: String): Boolean {
        if (wanted.isEmpty() || candidate.isEmpty()) return false
        if (wanted == candidate) return true
        val shorter = if (wanted.length <= candidate.length) wanted else candidate
        if (shorter.length < MIN_CONTAINMENT_LENGTH) return false
        return wanted.contains(candidate) || candidate.contains(wanted)
    }

    /**
     * Picks the artist a search was actually asking for.
     *
     * Deezer carries duplicate entries for the same act, most of them near-empty, and the one
     * a search returns first is regularly the wrong one: searching "Sezen Aksu" leads with an
     * entry that has 15 fans and no related artists, while the real one has over a million.
     * Popularity settles that.
     *
     * The name filter has to come first, though, and cannot be dropped for being redundant:
     * "Tarkan" also returns Reynmen, who is more popular than Tarkan is.
     */
    fun bestMatch(query: String, candidates: List<DeezerArtist>): DeezerArtist? {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return null
        return candidates
            .filter { normalize(it.name) == wanted }
            .maxByOrNull { it.fanCount }
    }

    private const val MIN_CONTAINMENT_LENGTH = 4

    private val BRACKETED = Regex("[\\(\\[][^\\)\\]]*[\\)\\]]")
    private val FEATURING = Regex("\\b(feat|ft|featuring|with|prod)\\b.*")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
}
