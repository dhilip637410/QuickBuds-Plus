package com.spizganed.quickbuds.calls

import java.util.Locale

/**
 * A spoken reply to "X is calling. Shall I pick up?": YES, NO or UNCLEAR. English and Tamil, in Tamil script and
 * in the romanised spelling people dictate. Pure, so every rule is tested (VoicePhrasesTest).
 *
 * The rules, each load-bearing:
 *  - NO before YES: "calla edukatha" ("don't take the call") contains "edu" ("take it").
 *  - Latin script matches whole words: "no" must not fire inside "know", "now" or "nothing".
 *  - Tamil script matches as a substring: it agglutinates, and word boundaries do not sit where Tamil's do.
 *  - No bare "dont" / "do not": "I don't know" would decline a real caller. Only "dont" with the action.
 *  - Toward UNCLEAR: a YES overlapping a NO phrase ("எடு" in "எடுக்காதே") is part of the NO. A YES that stands on its
 *    own next to a NO ("no... yes, take it") is a contradiction: UNCLEAR, never a guess. A wrong NO hangs up on
 *    a real caller; UNCLEAR only lets the phone ring.
 */
object VoicePhrases {

    enum class Answer { YES, NO, UNCLEAR }

    val NO = listOf(
        "calla edukatha", "call edukatha", "illa edukatha", "edukatha", "edukkatha", "edukkadha", "kandukkadha",
        "vendaam", "vendam", "venda", "vena",
        "வேண்டாம்", "வேண்டா", "எடுக்காதே",
        "no", "nope", "reject", "decline", "ignore", "not now", "cancel",
        "dont pick", "dont answer", "dont take", "do not pick", "do not answer",
        "cut the call", "cut it", "leave it"
    )

    val YES = listOf(
        "calla edu", "callah edu", "call edu", "edukalam", "edukka", "eduka", "edu",
        "ama", "aama", "aam", "sari", "seri", "pick pannu",
        "ஆமா", "ஆம்", "சரி", "எடு",
        "yes", "yeah", "yep", "yup", "ok", "okay", "sure", "answer",
        "pick up", "pick", "attend", "accept", "take it", "go ahead"
    )

    /** Lower case, apostrophes dropped ("don't" -> "dont"), other punctuation to spaces, single spaces. */
    fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace("'", "").replace("’", "")
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
        .trim()

    private fun isTamil(phrase: String) = phrase.any { it in '஀'..'௿' }

    /** Where [phrase] occurs in [t]: Tamil as a substring, Latin on whole words. */
    private fun spans(t: String, phrase: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        if (isTamil(phrase)) {
            var i = t.indexOf(phrase)
            while (i >= 0) { out += i until i + phrase.length; i = t.indexOf(phrase, i + 1) }
        } else {
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])").findAll(t).forEach { out += it.range }
        }
        return out
    }

    fun classify(text: String): Answer {
        val t = normalize(text)
        if (t.isEmpty()) return Answer.UNCLEAR
        val noSpans = NO.flatMap { spans(t, it) }
        val yesSpans = YES.flatMap { spans(t, it) }
            // A YES that overlaps a NO phrase belongs to the NO ("edu" in "calla edukatha", "pick up" in "dont pick up").
            .filter { y -> noSpans.none { n -> y.first <= n.last && n.first <= y.last } }
        return when {
            noSpans.isNotEmpty() && yesSpans.isNotEmpty() -> Answer.UNCLEAR
            noSpans.isNotEmpty() -> Answer.NO
            yesSpans.isNotEmpty() -> Answer.YES
            else -> Answer.UNCLEAR
        }
    }

    /**
     * Several recognizer hypotheses: act only when every one that says something agrees. Mixed answers, or
     * none, are UNCLEAR.
     */
    fun classifyAll(hypotheses: List<String>): Answer {
        val said = hypotheses.map { classify(it) }.filter { it != Answer.UNCLEAR }.toSet()
        return said.singleOrNull() ?: Answer.UNCLEAR
    }
}
