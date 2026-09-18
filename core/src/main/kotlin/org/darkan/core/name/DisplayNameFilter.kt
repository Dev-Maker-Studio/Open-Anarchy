package org.darkan.core.name

enum class DisplayNameVerdict { CLEAN, PROFANE, IMPERSONATION }

data class DisplayNameMatch(val verdict: DisplayNameVerdict, val term: String)

/**
 * Matches a display name against [ProfanityTerms] through three forms, each closing an evasion the
 * others leave open: every word on its own, every word with repeated letters squeezed out
 * ("Niiigggerr"), and the whole name with its separators dropped ("F u c k"). Only the squeezed
 * form of a word that actually had a repeat is used, so "As" never collapses into "Ass".
 *
 * The separator-stripped form is scanned with the long terms only, because two innocent words run
 * together can spell a short one; the short terms reach it only when the name has a one or
 * two-letter word, which is the only way to split a short term across words.
 */
object DisplayNameFilter {

    private const val COMPACT_MIN_LENGTH = 6
    private const val SHORT_WORD_LENGTH = 2

    private val leet = mapOf(
        '0' to 'o', '3' to 'e', '4' to 'a', '5' to 's', '6' to 'g', '7' to 't', '8' to 'b', '9' to 'g',
        '@' to 'a', '$' to 's', '!' to 'i', '+' to 't', '£' to 'e', '(' to 'c', '<' to 'c',
    )

    private val squeezedInWord = ProfanityTerms.inWord.associateBy { it.squeeze() }
    private val squeezedWholeWord = ProfanityTerms.wholeWord.associateBy { it.squeeze() }
    private val longInWord = ProfanityTerms.inWord.filter { it.length >= COMPACT_MIN_LENGTH }.toSet()

    fun isAcceptable(name: String): Boolean = match(name) == null

    fun match(name: String): DisplayNameMatch? = matchIn(name, oneAsL = false) ?: matchIn(name, oneAsL = true)

    private fun matchIn(name: String, oneAsL: Boolean): DisplayNameMatch? {
        val words = name.split(Regex("[^A-Za-z0-9@$!+£(<]+"))
            .map { it.normalize(oneAsL) }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        for (word in words) {
            if (word in ProfanityTerms.allowed) continue
            impersonationOf(word)?.let { return it }
            wholeWordMatch(word)?.let { return it }
            inWordMatch(word, ProfanityTerms.inWord)?.let { return it }
        }

        val scannable = words.filterNot { it in ProfanityTerms.allowed }
        if (scannable.size < 2) return null
        val compact = scannable.joinToString("")
        if (compact in ProfanityTerms.allowed) return null
        impersonationOf(compact)?.let { return it }
        wholeWordMatch(compact)?.let { return it }
        val hasShortWord = scannable.any { it.length <= SHORT_WORD_LENGTH }
        return inWordMatch(compact, if (hasShortWord) ProfanityTerms.inWord else longInWord)
    }

    private fun impersonationOf(word: String): DisplayNameMatch? =
        if (word in ProfanityTerms.impersonation) DisplayNameMatch(DisplayNameVerdict.IMPERSONATION, word) else null

    private fun wholeWordMatch(word: String): DisplayNameMatch? {
        ProfanityTerms.wholeWord.firstOrNull { it == word }?.let { return profane(it) }
        val squeezed = word.squeeze()
        if (squeezed == word) return null
        return squeezedWholeWord[squeezed]?.let { profane(it) }
    }

    private fun inWordMatch(word: String, terms: Set<String>): DisplayNameMatch? {
        terms.firstOrNull { word.contains(it) }?.let { return profane(it) }
        val squeezed = word.squeeze()
        if (squeezed == word) return null
        return squeezedInWord.entries
            .firstOrNull { (key, term) -> term in terms && squeezed.contains(key) }
            ?.let { profane(it.value) }
    }

    private fun profane(term: String) = DisplayNameMatch(DisplayNameVerdict.PROFANE, term)

    private fun String.normalize(oneAsL: Boolean): String {
        val out = StringBuilder(length)
        for (raw in lowercase()) {
            val ch = if (raw == '1') (if (oneAsL) 'l' else 'i') else leet[raw] ?: raw
            if (ch in 'a'..'z') out.append(ch)
        }
        return out.toString()
    }

    private fun String.squeeze(): String {
        val out = StringBuilder(length)
        for (ch in this) if (out.isEmpty() || out.last() != ch) out.append(ch)
        return out.toString()
    }
}
